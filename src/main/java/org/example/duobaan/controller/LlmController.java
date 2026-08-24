package org.example.duobaan.controller;

import java.util.List;
import java.util.concurrent.ExecutorService;

import org.example.duobaan.model.ChatMode;
import org.example.duobaan.model.ChatMessage;
import org.example.duobaan.model.TaskGroup;
import org.example.duobaan.model.dto.ChatRequest;
import org.example.duobaan.model.dto.ChatResponse;
import org.example.duobaan.model.dto.ParseTasksRequest;
import org.example.duobaan.model.dto.ParsedTask;
import org.example.duobaan.service.ChatService;
import org.example.duobaan.service.LlmGatewayService;
import org.example.duobaan.service.TaskService;
import org.example.duobaan.service.WeatherService;
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
 * 每次对话(user+assistant)均写入 chat_message 表，每 mode 自动限长 50 条（ChatService.appendAndTrim）。
 */
@RestController
@RequestMapping("/api/llm")
public class LlmController {

    private final LlmGatewayService llmGateway;
    private final WeatherService weatherService;
    private final TaskService taskService;
    private final ChatService chatService;
    private final ExecutorService sseExecutor;

    public LlmController(LlmGatewayService llmGateway, WeatherService weatherService,
                         TaskService taskService, ChatService chatService,
                         @Qualifier("sseExecutor") ExecutorService sseExecutor) {
        this.llmGateway = llmGateway;
        this.weatherService = weatherService;
        this.taskService = taskService;
        this.chatService = chatService;
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
        String systemContext = "天气：" + weatherService.now().summary()
                + "\n" + taskService.contextSummary();

        // 1) 入库用户消息
        if (!userMsg.isBlank()) {
            chatService.appendAndTrim(mode, "user", userMsg);
        }
        // 2) 调用大模型
        ChatResponse resp = llmGateway.chat(userMsg, mode, systemContext);
        // 3) 入库助手回复（含降级/错误提示也保存，刷新后看到一样的内容）
        chatService.appendAndTrim(mode, "assistant", resp.reply());
        return resp;
    }

    /**
     * 流式对话：SSE 逐 token 推送（事件 delta），结束发 done，异常发 error。
     * 流式开始前入库用户消息，流式过程中累积 delta 到 StringBuilder，
     * 结束/异常时统一入库助手完整回复，刷新后能恢复。
     */
    @PostMapping("/chat/stream")
    public SseEmitter chatStream(@RequestBody ChatRequest req) {
        // 0 = 不超时，由流式结束自行 complete
        SseEmitter emitter = new SseEmitter(0L);
        ChatMode mode = req.mode() != null ? req.mode() : ChatMode.WORK;
        String userMsg = req.message() == null ? "" : req.message();
        String systemContext = "天气：" + weatherService.now().summary()
                + "\n" + taskService.contextSummary();

        // 1) 入库用户消息（在执行线程内做，避免阻塞 sse 返回；但这里很快，先做也可以）
        if (!userMsg.isBlank()) {
            chatService.appendAndTrim(mode, "user", userMsg);
        }

        StringBuilder assistantReply = new StringBuilder();

        sseExecutor.execute(() -> {
            try {
                llmGateway.chatStream(userMsg, mode, systemContext, chunk -> {
                    // 累积完整回复，入库时用
                    if (chunk != null) assistantReply.append(chunk);
                    try {
                        emitter.send(SseEmitter.event().name("delta").data(chunk == null ? "" : chunk));
                    } catch (Exception ignore) {
                        // 客户端可能已断开，停止再发
                    }
                });
                // 正常结束 → 入库助手完整回复 + 发 done
                chatService.appendAndTrim(mode, "assistant", assistantReply.toString());
                try {
                    emitter.send(SseEmitter.event().name("done").data(""));
                } catch (Exception ignore) {
                }
                emitter.complete();
            } catch (Exception e) {
                // 异常：把已收到的 delta + 错误提示 一起入库
                String finalReply = assistantReply + "\n[流式错误: " + e.getMessage() + "]";
                chatService.appendAndTrim(mode, "assistant", finalReply);
                try {
                    emitter.send(SseEmitter.event().name("error").data(e.getMessage()));
                } catch (Exception ignore) {
                }
                emitter.complete();
            }
        });
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
}
