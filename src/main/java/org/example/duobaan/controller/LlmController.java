package org.example.duobaan.controller;

import java.util.List;
import java.util.concurrent.ExecutorService;

import org.example.duobaan.model.ChatMode;
import org.example.duobaan.model.TaskGroup;
import org.example.duobaan.model.dto.ChatRequest;
import org.example.duobaan.model.dto.ChatResponse;
import org.example.duobaan.model.dto.ParseTasksRequest;
import org.example.duobaan.model.dto.ParsedTask;
import org.example.duobaan.service.LlmGatewayService;
import org.example.duobaan.service.TaskService;
import org.example.duobaan.service.WeatherService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 大模型对话接口：
 * - /chat：非流式对话（保留，兼容旧调用）
 * - /chat/stream：流式对话（SSE，逐 token 推送）
 * - /parse-tasks：自然语言指令拆单，返回结构化任务列表
 * 每次调用自动拼接天气与流程表上下文。
 */
@RestController
@RequestMapping("/api/llm")
public class LlmController {

    private final LlmGatewayService llmGateway;
    private final WeatherService weatherService;
    private final TaskService taskService;
    private final ExecutorService sseExecutor;

    public LlmController(LlmGatewayService llmGateway, WeatherService weatherService,
            TaskService taskService, @Qualifier("sseExecutor") ExecutorService sseExecutor) {
        this.llmGateway = llmGateway;
        this.weatherService = weatherService;
        this.taskService = taskService;
        this.sseExecutor = sseExecutor;
    }

    /** 非流式对话（兼容） */
    @PostMapping("/chat")
    public ChatResponse chat(@RequestBody ChatRequest req) {
        ChatMode mode = req.mode() != null ? req.mode() : ChatMode.WORK;
        String systemContext = "天气：" + weatherService.now().summary()
                + "\n" + taskService.contextSummary();
        return llmGateway.chat(req.message(), mode, systemContext);
    }

    /** 流式对话：SSE 逐 token 推送，事件名 delta；结束发 done */
    @PostMapping("/chat/stream")
    public SseEmitter chatStream(@RequestBody ChatRequest req) {
        // 0 = 不超时，由流式结束自行 complete
        SseEmitter emitter = new SseEmitter(0L);
        ChatMode mode = req.mode() != null ? req.mode() : ChatMode.WORK;
        String systemContext = "天气：" + weatherService.now().summary()
                + "\n" + taskService.contextSummary();

        sseExecutor.execute(() -> {
            try {
                llmGateway.chatStream(req.message(), mode, systemContext, chunk -> {
                    try {
                        emitter.send(SseEmitter.event().name("delta").data(chunk));
                    } catch (Exception ignore) {
                        // 客户端可能已断开
                    }
                });
                emitter.send(SseEmitter.event().name("done").data(""));
                emitter.complete();
            } catch (Exception e) {
                try {
                    emitter.send(SseEmitter.event().name("error").data(e.getMessage()));
                } catch (Exception ignore) {
                }
                emitter.complete();
            }
        });
        return emitter;
    }

    /** 拆单：把自然语言指令拆成任务列表（结构化） */
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
}
