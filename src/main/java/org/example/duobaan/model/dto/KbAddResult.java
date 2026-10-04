package org.example.duobaan.model.dto;

/**
 * 写入文档的返回结果：分块数 + 向量化是否就绪。
 */
public record KbAddResult(
        int chunkCount,
        boolean embeddingReady) {
}