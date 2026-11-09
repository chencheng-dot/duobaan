package org.example.duobaan.model.dto;

/**
 * RAG 运行状态（GET /api/knowledge/status）。
 *
 * <p>embeddingReady / redisOk 只说明「配置存在 / Redis 连通」，
 * 并不说明向量真的写进去了——历史上正是这一点让「向量化额度耗尽、
 * 检索永远返回空」表现为「知识库没有相关内容」。因此这里额外给出
 * 分块数、向量数与 degraded 标记，供前端把这类静默失效显式展示出来。
 *
 * @param embeddingReady 向量化配置是否就绪（有激活的 EMBEDDING 配置且模型 ID 非空）
 * @param redisOk        Redis 是否连通
 * @param kbCount        知识库数量
 * @param chunkCount     MySQL 中的分块总数
 * @param vectorCount    Redis 中已登记的向量数（按知识库 chunk 集合 SCARD 汇总）
 * @param degraded       是否存在「检索不可用 / 不完整」的情况（缺配置、Redis 不通、向量缺失或残留）
 * @param hint           面向用户的一句话说明（降级原因 + 下一步动作），正常时也可能为提示语
 */
public record RagStatus(
        boolean embeddingReady,
        boolean redisOk,
        int kbCount,
        long chunkCount,
        long vectorCount,
        boolean degraded,
        String hint) {
}
