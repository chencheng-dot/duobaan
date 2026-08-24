package org.example.duobaan.controller;

import org.example.duobaan.model.ChatMode;
import org.example.duobaan.model.dto.ChatRequest;
import org.example.duobaan.model.dto.ChatResponse;
import org.example.duobaan.service.LlmGatewayService;
import org.example.duobaan.service.TaskService;
import org.example.duobaan.service.WeatherService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 大模型对话接口：按模式加载办公/多巴胺双 Agent，并自动拼接天气与流程表上下文。
 * 本期为非流式；流式（SSE）在里程碑 3 接入。
 */
@RestController
@RequestMapping("/api/llm")
public class LlmController {

    private final LlmGatewayService llmGateway;
    private final WeatherService weatherService;
    private final TaskService taskService;

    public LlmController(LlmGatewayService llmGateway, WeatherService weatherService, TaskService taskService) {
        this.llmGateway = llmGateway;
        this.weatherService = weatherService;
        this.taskService = taskService;
    }

    @PostMapping("/chat")
    public ChatResponse chat(@RequestBody ChatRequest req) {
        ChatMode mode = req.mode() != null ? req.mode() : ChatMode.WORK;
        String systemContext = "天气：" + weatherService.now().summary()
                + "\n" + taskService.contextSummary();
        return llmGateway.chat(req.message(), mode, systemContext);
    }
}
