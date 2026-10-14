package org.example.duobaan.service;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import org.example.duobaan.config.DuobaanProperties;
import org.example.duobaan.model.ApiProfileType;
import org.example.duobaan.model.ChatMode;
import org.example.duobaan.model.TaskGroup;
import org.example.duobaan.model.dto.ChatResponse;
import org.example.duobaan.model.dto.LlmChat;
import org.example.duobaan.model.dto.LlmChat.Message;
import org.example.duobaan.model.dto.LlmConfigDTO;
import org.example.duobaan.model.dto.LlmStreamChunk;
import org.example.duobaan.model.dto.ParsedTask;
import org.example.duobaan.util.OutboundUrlGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.ObjectMapper;

/**
 * 大模型统一网关：屏蔽厂商差异，按模式加载双系统提示词（办公 Agent / 多巴胺餐食 Agent）。
 * 支持非流式对话、流式对话（SSE）、任务拆单（结构化 JSON）。
 * 配置优先级：数据库运行时配置 > application.properties 默认值。
 * 未配置 Key 时返回降级提示，保证平台可独立启动。
 */
@Service
public class LlmGatewayService {

    private static final Logger log = LoggerFactory.getLogger(LlmGatewayService.class);

    /** 用词边界匹配状态码，避免把 "4040ms" 这类子串误判成 HTTP 404 / 401 */
    private static final Pattern AUTH_FAILED = Pattern.compile("\\b401\\b");
    private static final Pattern NOT_FOUND = Pattern.compile("\\b404\\b");

    private final RestClient restClient;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final DuobaanProperties props;
    private final ConfigService configService;
    private final ApiProfileService apiProfileService;

    public LlmGatewayService(RestClient externalRestClient, HttpClient httpClient,
                             ObjectMapper objectMapper, DuobaanProperties props,
                             ConfigService configService, ApiProfileService apiProfileService) {
        this.restClient = externalRestClient;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.props = props;
        this.configService = configService;
        this.apiProfileService = apiProfileService;
    }

    /** 取当前生效的大模型配置：优先从 api_profile.active 读，否则回退 system_config/properties 默认值 */
    private LlmConfigDTO currentConfig() {
        return apiProfileService.getActivePlain(ApiProfileType.LLM)
                .map(p -> new LlmConfigDTO(
                        p.getProvider() == null ? "custom" : p.getProvider(),
                        p.getBaseUrl(),
                        p.getApiKey(),
                        p.getModel(),
                        p.getTimeoutSeconds() == null ? props.getLlm().getTimeoutSeconds() : p.getTimeoutSeconds()))
                .orElseGet(configService::getLlmConfig);
    }

    private boolean isConfigured(LlmConfigDTO cfg) {
        return cfg.apiKey() != null && !cfg.apiKey().isBlank();
    }

    /**
     * 单轮对话（非流式）：用系统提示词 + 用户消息调用大模型。
     */
    public ChatResponse chat(String userMessage, ChatMode mode, String systemContext) {
        LlmConfigDTO cfg = currentConfig();
        if (!isConfigured(cfg)) {
            return degradedReply(userMessage, mode);
        }
        List<Message> messages = new ArrayList<>();
        messages.add(new Message("system", buildSystemPrompt(mode, systemContext)));
        messages.add(new Message("user", userMessage));
        String url = cfg.baseUrl() + "/chat/completions";
        OutboundUrlGuard.verify(url);   // 出网地址守卫：阻断内网/环回/元数据地址
        try {
            LlmChat.Request request = new LlmChat.Request(cfg.model(), messages);
            LlmChat.Response resp = restClient.post()
                    .uri(url)
                    .header("Authorization", "Bearer " + cfg.apiKey())
                    .header("Content-Type", "application/json")
                    .body(request)
                    .retrieve()
                    .body(LlmChat.Response.class);
            if (resp == null || resp.choices() == null || resp.choices().isEmpty()) {
                return new ChatResponse("大模型返回为空，请稍后重试。", true);
            }
            return new ChatResponse(resp.choices().get(0).message().content(), false);
        } catch (Exception e) {
            // 原始 message 可能含上游报文 / 解析到的内网地址，只写日志；对用户只回可读文案
            log.warn("非流式对话调用失败：{}", e.getMessage());
            return new ChatResponse(friendlyTransportError(e.getMessage()), true);
        }
    }

    /**
     * 流式对话：调用大模型 stream 接口，逐 token 回调 onDelta。
     * 调用方负责把 onDelta 投递到 SSE 通道。
     *
     * @param cancelled 取消信号：返回 true 时立即停止读上游并关闭连接，
     *                  避免客户端断开后线程与上游连接一直被占到生成结束（32 个 SSE 线程会被打满）
     */
    public void chatStream(String userMessage, ChatMode mode, String systemContext,
                           BooleanSupplier cancelled, Consumer<String> onDelta) {
        LlmConfigDTO cfg = currentConfig();
        if (!isConfigured(cfg)) {
            onDelta.accept(degradedHint(mode));
            return;
        }
        String url = cfg.baseUrl() + "/chat/completions";
        OutboundUrlGuard.verify(url);   // 出网地址守卫：阻断内网/环回/元数据地址
        try {
            // 用显式 Map 构造请求体，替代原来的「双大括号匿名内部类」：
            // 后者会为每个请求多生成一个匿名类（持有外层 this，易造成隐式引用泄漏），可读性也差。
            Map<String, Object> payload = new HashMap<>();
            payload.put("model", cfg.model());
            payload.put("stream", true);
            payload.put("messages", List.of(
                    new Message("system", buildSystemPrompt(mode, systemContext)),
                    new Message("user", userMessage)));
            String body = objectMapper.writeValueAsString(payload);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + cfg.apiKey())
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(cfg.timeoutSeconds()))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<InputStream> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());
            int status = resp.statusCode();
            if (status >= 400) {
                // 必须消费并关闭响应体：原来直接 return 会让连接与流悬挂，长期运行会耗尽连接池。
                // 排障需要的上游原文只写日志（截前 512 字节），对用户只回可读文案。
                try (InputStream in = resp.body()) {
                    byte[] preview = in.readNBytes(512);
                    if (preview.length > 0) {
                        log.warn("LLM 流式上游返回 {}：{}", status, new String(preview, StandardCharsets.UTF_8));
                    }
                }
                onDelta.accept(friendlyHttpError(status));
                return;
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // 客户端已断开 → 停止读上游；break 即触发 try-with-resources 关闭连接，上游也随之停止计费
                    if (cancelled != null && cancelled.getAsBoolean()) {
                        break;
                    }
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String data = line.substring(5).trim();
                    if (data.equals("[DONE]")) {
                        break;
                    }
                    if (data.isEmpty()) {
                        continue;
                    }
                    try {
                        LlmStreamChunk chunk = objectMapper.readValue(data, LlmStreamChunk.class);
                        if (chunk.choices() != null && !chunk.choices().isEmpty()) {
                            String content = chunk.choices().get(0).delta().content();
                            if (content != null) {
                                onDelta.accept(content);
                            }
                        }
                    } catch (Exception ignore) {
                        // 跳过无法解析的分片
                    }
                }
            }
        } catch (Exception e) {
            onDelta.accept("\n" + friendlyTransportError(e.getMessage()));
        }
    }

    /**
     * 拆单：让大模型把自然语言指令拆成任务列表，返回结构化 JSON。
     * 解析失败时返回空列表，由调用方降级处理。
     */
    public List<ParsedTask> parseTasks(String userMessage, TaskGroup defaultGroup) {
        LlmConfigDTO cfg = currentConfig();
        if (!isConfigured(cfg)) {
            return List.of();
        }
        String systemPrompt = """
                你是任务拆解助手。把用户指令拆成可执行的任务列表。
                只返回 JSON 数组，格式：[{"title":"任务标题","group":"TODAY 或 TOMORROW"}]
                不要任何解释、不要 markdown 代码块、不要多余文字。
                group 取今天能做的用 TODAY，需要明天做的用 TOMORROW。
                """;
        String url = cfg.baseUrl() + "/chat/completions";
        OutboundUrlGuard.verify(url);   // 出网地址守卫：阻断内网/环回/元数据地址
        try {
            List<Message> messages = List.of(
                    new Message("system", systemPrompt),
                    new Message("user", userMessage));
            LlmChat.Request request = new LlmChat.Request(cfg.model(), messages);
            LlmChat.Response resp = restClient.post()
                    .uri(url)
                    .header("Authorization", "Bearer " + cfg.apiKey())
                    .header("Content-Type", "application/json")
                    .body(request)
                    .retrieve()
                    .body(LlmChat.Response.class);
            if (resp == null || resp.choices() == null || resp.choices().isEmpty()) {
                return List.of();
            }
            String reply = resp.choices().get(0).message().content();
            return parseJsonTasks(reply, defaultGroup);
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 从大模型回复中提取 JSON 任务数组，兼容 markdown 代码块与多余文字 */
    @SuppressWarnings("unchecked")
    private List<ParsedTask> parseJsonTasks(String reply, TaskGroup defaultGroup) {
        String json = extractJsonArray(reply);
        if (json == null) {
            return List.of();
        }
        try {
            List<Object> list = objectMapper.readValue(json, List.class);
            List<ParsedTask> result = new ArrayList<>();
            for (Object o : list) {
                if (!(o instanceof java.util.Map<?, ?> m)) {
                    continue;
                }
                String title = String.valueOf(m.get("title"));
                String groupStr = String.valueOf(m.get("group"));
                TaskGroup g = normalizeGroup(groupStr, defaultGroup);
                if (!title.isBlank()) {
                    result.add(new ParsedTask(title, g.name()));
                }
            }
            return result;
        } catch (Exception e) {
            return List.of();
        }
    }

    private String extractJsonArray(String reply) {
        if (reply == null || reply.isBlank()) {
            return null;
        }
        int start = reply.indexOf('[');
        int end = reply.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return null;
        }
        return reply.substring(start, end + 1);
    }

    private TaskGroup normalizeGroup(String groupStr, TaskGroup fallback) {
        if (groupStr == null) {
            return fallback;
        }
        try {
            return TaskGroup.valueOf(groupStr.trim().toUpperCase());
        } catch (Exception e) {
            return fallback;
        }
    }

    private String buildSystemPrompt(ChatMode mode, String systemContext) {
        String base = switch (mode) {
            case WORK -> """
                    你是「多巴胺」平台的办公助手。角色：个人工作助理。
                    你的职责：
                    1. 帮用户规划今天或明天的安排，结合天气、时间与现有流程表给出可执行建议。
                    2. 把用户的自然语言指令拆解为可勾选的任务条目，必要时用 JSON 数组列出任务标题。
                    3. 可起草周报、总结会议纪要、生成回复话术。
                    重要约束：你没有直接修改流程表的权限，不能"已经把任务移到明天/改状态"。
                    如果用户要求移动、删除、修改任务状态，请明确告知他去点击流程表上对应的按钮（如「→明日」「✓」等），
                    或直接告诉他需要在界面上手动操作，绝不要声称你已经完成了这些操作。
                    回答简洁、可执行，避免空话。
                    """;
            case DOPAMINE -> """
                    你是「多巴胺」平台的生活决策助手。角色：帮用户决定这一餐吃什么。
                    你的职责：
                    1. 综合天气、心情、口味、用餐方式（外卖/堂吃/自己做），推荐一餐。
                    2. 给出餐食名、推荐理由、就近或做法建议、预计花费。
                    3. 若用户没填某项，用常识补全并说明。
                    只推荐一餐为主，最多补两个备选。语气轻松。
                    """;
        };
        if (systemContext != null && !systemContext.isBlank()) {
            base += "\n当前情境上下文：\n" + systemContext;
        }
        return base;
    }

    private ChatResponse degradedReply(String userMessage, ChatMode mode) {
        return new ChatResponse(degradedHint(mode), true);
    }

    /** 未配置大模型的直白提示 */
    private String degradedHint(ChatMode mode) {
        String scene = (mode == ChatMode.WORK) ? "办公助手" : "美食推荐";
        return "⚠️ 未配置大模型，请先到「设置」页面选择厂商并填写 API Key，即可使用" + scene + "功能。";
    }

    /** 把 HTTP 状态码翻译成人话错误 */
    private String friendlyHttpError(int status) {
        return switch (status) {
            case 401 -> "❌ API Key 无效或已过期，请检查 Key 是否填写正确。";
            case 403 -> "❌ API Key 无权访问该接口，请确认账号权限或套餐余量。";
            case 404 -> "❌ 接口地址错误（HTTP 404），请检查 Base URL 是否正确。";
            case 429 -> "❌ 请求过于频繁（HTTP 429），请稍后再试或升级套餐。";
            case 500, 502, 503 -> "❌ 大模型服务端异常（HTTP " + status + "），请稍后重试。";
            default -> "❌ 大模型调用失败（HTTP " + status + "），请检查配置。";
        };
    }

    /**
     * 传输层错误 → 直白提示：只做关键词映射，原始 message 一律不外泄（调用方负责把它写进日志）。
     * 公开为 static 供 controller 的 SSE catch 复用，避免再写一个近乎重复的 friendly* 方法。
     */
    public static String friendlyTransportError(String rawMsg) {
        if (rawMsg == null) return "❌ 连接中断，请重试。";
        if (rawMsg.contains("timed out") || rawMsg.contains("timeout")) {
            return "❌ 请求超时，请检查网络或增大超时时间。";
        }
        if (rawMsg.contains("Connection refused") || rawMsg.contains("No route")) {
            return "❌ 无法连接到接口地址，请检查 Base URL 和网络。";
        }
        if (rawMsg.contains("禁止访问") || rawMsg.contains("出网地址")) {
            return "❌ 出网地址被安全策略拒绝，请检查 Base URL 是否指向公网地址。";
        }
        if (AUTH_FAILED.matcher(rawMsg).find()) return "❌ API Key 无效，请检查 Key 是否填写正确。";
        if (NOT_FOUND.matcher(rawMsg).find()) return "❌ 接口地址错误，请检查 Base URL。";
        return "❌ 连接中断，请稍后重试。";
    }
}
