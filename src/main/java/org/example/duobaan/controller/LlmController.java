package org.example.duobaan.controller;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.example.duobaan.model.ChatMode;
import org.example.duobaan.model.ChatMessage;
import org.example.duobaan.model.TaskGroup;
import org.example.duobaan.model.dto.ChatRequest;
import org.example.duobaan.model.dto.ChatResponse;
import org.example.duobaan.model.dto.ParseTasksRequest;
import org.example.duobaan.model.dto.ParsedTask;
import org.example.duobaan.service.ChatAppendRetry;
import org.example.duobaan.service.ChatService;
import org.example.duobaan.service.KnowledgeService;
import org.example.duobaan.service.LlmGatewayService;
import org.example.duobaan.service.TaskService;
import org.example.duobaan.service.WeatherService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 大模型对话接口：
 * - /chat：非流式对话（保留，兼容旧调用）
 * - /chat/stream：流式对话（SSE，逐 token 推送）
 * - /parse-tasks：自然语言指令拆单，返回结构化任务列表
 * - /history：拉取某 mode 下最近 N 条历史（用于刷新页面后恢复）
 * 每次对话(user+assistant)均写入 chat_message 表，每 mode 自动限长 50 条（ChatAppendRetry.appendAndTrimWithRetry）。
 */
@RestController
@RequestMapping("/api/llm")
public class LlmController {

    private static final Logger log = LoggerFactory.getLogger(LlmController.class);
    private static final long SSE_TIMEOUT_MS = 5 * 60 * 1000L;

    private final LlmGatewayService llmGateway;
    private final WeatherService weatherService;
    private final TaskService taskService;
    private final ChatService chatService;
    private final ChatAppendRetry chatAppendRetry;
    private final KnowledgeService knowledgeService;
    private final ExecutorService sseExecutor;

    public LlmController(LlmGatewayService llmGateway, WeatherService weatherService,
                         TaskService taskService, ChatService chatService,
                         ChatAppendRetry chatAppendRetry,
                         KnowledgeService knowledgeService,
                         @Qualifier("sseExecutor") ExecutorService sseExecutor) {
        this.llmGateway = llmGateway;
        this.weatherService = weatherService;
        this.taskService = taskService;
        this.chatService = chatService;
        this.chatAppendRetry = chatAppendRetry;
        this.knowledgeService = knowledgeService;
        this.sseExecutor = sseExecutor;
    }

    /**
     * 获取某 mode 下对话历史，按时间正序返回（最旧 → 最新）。
     * 前端 ChatPanel 在 onMounted 时调用，恢复上一次对话内容。
     *
     * @param mode  WORK | DOPAMINE，默认 WORK
     * @param limit 返回条数上限，默认 50，最大被 ChatService.MAX_HISTORY_PER_MODE 截断
     */
    @GetMapping("/history")
    public List<ChatMessage> history(
            @RequestParam(defaultValue = "WORK") String mode,
            @RequestParam(defaultValue = "50") int limit) {
        ChatMode m = parseMode(mode);
        return chatService.getHistory(m, limit);
    }

    /** 非流式对话（兼容）— 两边都入库 */
    @PostMapping("/chat")
    public ChatResponse chat(@RequestBody ChatRequest req) {
        ChatMode mode = req.mode() != null ? req.mode() : ChatMode.WORK;
        String userMsg = req.message() == null ? "" : req.message();
        String systemContext = buildSystemContext(userMsg);

        // 1) 入库用户消息
        if (!userMsg.isBlank()) {
            chatAppendRetry.appendAndTrimWithRetry(mode, "user", userMsg);
        }
        // 2) 调用大模型
        ChatResponse resp = llmGateway.chat(userMsg, mode, systemContext);
        // 3) 入库助手回复（含降级/错误提示也保存，刷新后看到一样的内容）
        chatAppendRetry.appendAndTrimWithRetry(mode, "assistant", resp.reply());
        return resp;
    }

    /**
     * 流式对话：SSE 逐 token 推送（事件 delta），结束发 done，异常发 error。
     * 流式开始前入库用户消息，流式过程中累积 delta 到 StringBuilder，
     * 结束/异常时统一入库助手完整回复，刷新后能恢复。
     */
    @PostMapping("/chat/stream")
    public SseEmitter chatStream(@RequestBody ChatRequest req) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        ChatMode mode = req.mode() != null ? req.mode() : ChatMode.WORK;
        String userMsg = req.message() == null ? "" : req.message();
        String systemContext = buildSystemContext(userMsg);

        // 1) 入库用户消息
        if (!userMsg.isBlank()) {
            chatAppendRetry.appendAndTrimWithRetry(mode, "user", userMsg);
        }

        StringBuilder assistantReply = new StringBuilder();
        AtomicBoolean closed = new AtomicBoolean(false);
        emitter.onCompletion(() -> closed.set(true));
        emitter.onTimeout(() -> { closed.set(true); emitter.complete(); });
        emitter.onError(e -> closed.set(true));

        try {
            sseExecutor.execute(() -> {
                try {
                    llmGateway.chatStream(userMsg, mode, systemContext, closed::get, chunk -> {
                        // 累积完整回复，入库时用
                        if (chunk != null) assistantReply.append(chunk);
                        if (closed.get()) return;
                        try {
                            emitter.send(SseEmitter.event().name("delta").data(chunk == null ? "" : chunk));
                        } catch (Exception e) {
                            // 客户端可能已断开，停止再发
                            closed.set(true);
                        }
                    });
                    // 正常结束 → 入库助手完整回复 + 发 done
                    chatAppendRetry.appendAndTrimWithRetry(mode, "assistant", assistantReply.toString());
                    if (!closed.get()) {
                        try {
                            emitter.send(SseEmitter.event().name("done").data(""));
                        } catch (Exception ignore) {
                        }
                        emitter.complete();
                    }
                } catch (Exception e) {
                    // 异常：把已收到的 delta + 可读错误提示 一起入库。
                    // e.getMessage() 可能含上游报文 / 解析到的内网地址，只进日志，绝不回吐前端或写入聊天记录
                    // （出网守卫的拒绝就抛在本 catch，是这里的常见来源）。
                    log.error("SSE 流式对话失败 mode={}", mode, e);
                    String friendly = LlmGatewayService.friendlyTransportError(e.getMessage());
                    chatAppendRetry.appendAndTrimWithRetry(mode, "assistant", assistantReply + "\n" + friendly);
                    if (!closed.get()) {
                        try {
                            emitter.send(SseEmitter.event().name("error").data(friendly));
                        } catch (Exception ignore) {
                        }
                        emitter.complete();
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            emitter.completeWithError(new IllegalStateException("当前流式会话过多，请稍后重试"));
        }
        return emitter;
    }

    /** 拆单：把自然语言指令拆成任务列表（结构化）。拆单对话本身不入库（和业务功能解耦） */
    @PostMapping("/parse-tasks")
    public List<ParsedTask> parseTasks(@RequestBody ParseTasksRequest req) {
        TaskGroup defaultGroup = TaskGroup.TODAY;
        if (req.group() != null && !req.group().isBlank()) {
            try {
                defaultGroup = TaskGroup.valueOf(req.group().trim().toUpperCase());
            } catch (Exception ignore) {
            }
        }
        return llmGateway.parseTasks(req.message(), defaultGroup);
    }

    private ChatMode parseMode(String m) {
        if (m == null || m.isBlank()) return ChatMode.WORK;
        try {
            return ChatMode.valueOf(m.trim().toUpperCase());
        } catch (Exception ignore) {
            return ChatMode.WORK;
        }
    }

    /**
     * 组装系统上下文：天气 + 流程表摘要 + 知识库检索结果（RAG）。
     * RAG 检索每次对话都执行，只有相似度超过阈值返回非空时才注入，保证「提及知识库内容时优先查询」。
     */
    private String buildSystemContext(String userMsg) {
        String base = "天气：" + weatherService.now().summary()
                + "\n" + taskService.contextSummary();
        // 过短消息（<2 字 / 纯符号）跳过 RAG 检索，避免无意义的全量向量扫描
        String rag = shouldRetrieve(userMsg) ? knowledgeService.retrieveContext(userMsg) : null;
        if (rag != null && !rag.isBlank()) {
            base += "\n" + rag;
        }
        return base;
    }

    /** 是否值得做知识库检索：至少 2 个字符且含中英文/数字 */
    private static boolean shouldRetrieve(String msg) {
        if (msg == null) return false;
        String t = msg.trim();
        if (t.length() < 2) return false;
        return t.chars().anyMatch(Character::isLetterOrDigit);
    }
}
