package org.example.duobaan.model.dto;

/**
 * 写入文档的返回结果。
 *
 * <p>历史问题：只返回「分块数 + 配置是否就绪」，于是「embedding 调了但一条向量都没写进去」
 * （额度耗尽、上游 4xx）与「正常入库」在接口上完全一样，前端只能显示「成功」。
 * 现在把真实写入的向量数与降级说明一起返回，0 向量时 embeddingReady 必然为 false。
 *
 * @param chunkCount      本次写入的分块数
 * @param vectorizedCount 本次成功写入 Redis 的向量数（0 表示这些分块暂时检索不到）
 * @param embeddingReady  向量化是否真正可用（未配置 / 全部失败 / 部分失败均为 false）
 * @param hint            面向用户的降级说明；正常时为 {@code null}，前端可直接展示
 */
public record KbAddResult(
        int chunkCount,
        int vectorizedCount,
        boolean embeddingReady,
        String hint) {
}
