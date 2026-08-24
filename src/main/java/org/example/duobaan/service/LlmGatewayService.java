package org.example.duobaan.service;

import java.util.ArrayList;
import java.util.List;

import org.example.duobaan.config.DuobaanProperties;
import org.example.duobaan.model.ChatMode;
import org.example.duobaan.model.dto.ChatResponse;
import org.example.duobaan.model.dto.LlmChat;
import org.example.duobaan.model.dto.LlmChat.Message;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * 大模型统一网关：屏蔽厂商差异，按模式加载双系统提示词（办公 Agent / 多巴胺餐食 Agent）。
 * 非流式优先；未配置 Key 时返回降级提示，保证平台可独立启动。
 */
@Service
public class LlmGatewayService {

    private final RestClient restClient;
    private final DuobaanProperties props;

    public LlmGatewayService(RestClient externalRestClient, DuobaanProperties props) {
        this.restClient = externalRestClient;
        this.props = props;
    }

    /**
     * 单轮对话：用系统提示词 + 用户消息调用大模型。
     *
     * @param systemContext 上下文摘要（天气/时间/流程表）拼进系统提示词
     */
    public ChatResponse chat(String userMessage, ChatMode mode, String systemContext) {
        DuobaanProperties.Llm cfg = props.getLlm();
        if (!cfg.isConfigured()) {
            return degradedReply(userMessage, mode);
        }

        List<Message> messages = new ArrayList<>();
        messages.add(new Message("system", buildSystemPrompt(mode, systemContext)));
        messages.add(new Message("user", userMessage));

        try {
            LlmChat.Request request = new LlmChat.Request(cfg.getModel(), messages);
            LlmChat.Response resp = restClient.post()
                    .uri(cfg.getBaseUrl() + "/chat/completions")
                    .header("Authorization", "Bearer " + cfg.getApiKey())
                    .header("Content-Type", "application/json")
                    .body(request)
                    .retrieve()
                    .body(LlmChat.Response.class);

            if (resp == null || resp.choices() == null || resp.choices().isEmpty()) {
                return new ChatResponse("大模型返回为空，请稍后重试。", true);
            }
            String reply = resp.choices().get(0).message().content();
            return new ChatResponse(reply, false);
        } catch (Exception e) {
            return new ChatResponse("大模型调用失败：" + e.getMessage(), true);
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
        String hint = switch (mode) {
            case WORK -> "（未配置大模型 Key，办公助手暂不可用。配置 duobaan.llm.api-key 后即可生效。）"
                    + "\n你说的：" + userMessage;
            case DOPAMINE -> "（未配置大模型 Key，餐食推荐暂不可用。配置 duobaan.llm.api-key 后即可生效。）"
                    + "\n你说的：" + userMessage;
        };
        return new ChatResponse(hint, true);
    }
}
