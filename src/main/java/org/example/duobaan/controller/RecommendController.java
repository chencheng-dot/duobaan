package org.example.duobaan.controller;

import org.example.duobaan.model.Task;
import org.example.duobaan.model.dto.ChatResponse;
import org.example.duobaan.model.dto.MealContext;
import org.example.duobaan.service.RecommendService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 多巴胺餐食推荐接口：提交情境 → 大模型推荐；采纳 → 写入今日流程表。
 */
@RestController
@RequestMapping("/api/recommend")
public class RecommendController {

    private final RecommendService recommendService;

    public RecommendController(RecommendService recommendService) {
        this.recommendService = recommendService;
    }

    @PostMapping("/meal")
    public ChatResponse recommend(@RequestBody MealContext ctx) {
        return recommendService.recommend(ctx);
    }

    @PostMapping("/adopt")
    public Task adopt(@RequestParam String mealTitle) {
        return recommendService.adoptMeal(mealTitle);
    }
}
