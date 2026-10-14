package org.example.duobaan.controller;

import org.example.duobaan.model.Task;
import org.example.duobaan.model.dto.ChatResponse;
import org.example.duobaan.model.dto.MealContext;
import org.example.duobaan.service.RecommendService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

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

    /**
     * 采纳推荐：把选中的餐食写入今日流程表。
     *
     * 契约保持 query 参数而<b>不</b>改成 @Valid @RequestBody DTO，原因：
     * 前端 frontend/src/api/index.js:118-119 的 adoptMeal() 就是
     * `POST /api/recommend/adopt?mealTitle=xxx`（body 固定为 '{}'），
     * 改成请求体 DTO 会直接破坏现有前端调用。
     *
     * 参数校验放在这里而不是 RecommendService：本次任务只允许改动本控制器等少数文件，
     * RecommendService.java 不在可改清单内。校验规则与 TaskRequest.title 一致
     * （非空 + ≤200 字），防止空标题/超长标题直达 task.title VARCHAR(200) 抛 500。
     */
    @PostMapping("/adopt")
    public Task adopt(@RequestParam String mealTitle) {
        if (mealTitle == null || mealTitle.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "餐食名称不能为空");
        }
        if (mealTitle.length() > 200) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "餐食名称不能超过 200 字");
        }
        return recommendService.adoptMeal(mealTitle);
    }
}
