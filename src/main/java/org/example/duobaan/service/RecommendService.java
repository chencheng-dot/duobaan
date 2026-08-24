package org.example.duobaan.service;

import org.example.duobaan.model.ChatMode;
import org.example.duobaan.model.Task;
import org.example.duobaan.model.TaskCategory;
import org.example.duobaan.model.TaskGroup;
import org.example.duobaan.model.dto.ChatResponse;
import org.example.duobaan.model.dto.MealContext;
import org.example.duobaan.repository.TaskRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 多巴胺餐食推荐编排：
 * 1. 接收 MealContext，补入实时天气作为上下文；
 * 2. 调用大模型（多巴胺餐食 Agent）给出一餐推荐；
 * 3. 采纳推荐 → 写入流程表「今日用餐」并标记完成（多巴胺释放闭环）。
 */
@Service
public class RecommendService {

    private final LlmGatewayService llmGateway;
    private final WeatherService weatherService;
    private final TaskRepository taskRepo;

    public RecommendService(LlmGatewayService llmGateway, WeatherService weatherService,
            TaskRepository taskRepo) {
        this.llmGateway = llmGateway;
        this.weatherService = weatherService;
        this.taskRepo = taskRepo;
    }

    public ChatResponse recommend(MealContext ctx) {
        String weatherSummary = weatherService.now().summary();
        String systemContext = "天气：" + weatherSummary + "\n" + ctx.summary();
        String userPrompt = "请根据以上情境，推荐我这一餐吃什么。";
        return llmGateway.chat(userPrompt, ChatMode.DOPAMINE, systemContext);
    }

    /** 采纳推荐：把餐食写入今日流程表并标记完成 */
    @Transactional
    public Task adoptMeal(String mealTitle) {
        Task t = new Task(mealTitle, TaskCategory.MEAL, TaskGroup.TODAY);
        t.setStatus(org.example.duobaan.model.TaskStatus.DONE);
        t.setSource(org.example.duobaan.model.TaskSource.LLM);
        return taskRepo.save(t);
    }
}
