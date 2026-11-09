package org.example.duobaan.model.dto;

import java.util.List;

/**
 * 检索结果 + 空结果原因（POST /api/knowledge/search/explain）。
 *
 * <p>为什么需要它：检索返回空列表可能是「真的没有相关知识」，也可能是
 * 「向量化额度耗尽 / Redis 空库 / 向量与正文不一致」——旧接口把两者混为一谈，
 * 排查时只能靠猜。这里把原因显式带出，让前端与运维能区分「没命中」和「链路坏了」。
 */
public record SearchExplain(Reason reason, String message, List<RetrievedChunk> chunks) {

    /** 空结果 / 异常结果的分类（JSON 序列化为名称，前端按名称匹配） */
    public enum Reason {
        /** 检索成功且有命中 */
        OK,
        /** 查询为空 */
        BLANK_QUERY,
        /** 向量化模型未配置（设置 → 向量化模型） */
        EMBEDDING_NOT_READY,
        /** 调用了向量化但失败（额度 / 鉴权 / 网络等，具体见 message） */
        EMBEDDING_FAILED,
        /** Redis 中没有可检索的向量：尚未入库、全部丢失，或 Redis 不可用 */
        INDEX_EMPTY,
        /** 有向量存在，但相似度全部低于阈值 */
        NO_HIT,
        /** 命中了向量，但对应分块正文已不存在（向量孤儿 / 数据不一致） */
        INDEX_INCONSISTENT
    }

    /** 是否真正命中（reason == OK 且列表非空） */
    public boolean hit() {
        return reason == Reason.OK && chunks != null && !chunks.isEmpty();
    }
}
