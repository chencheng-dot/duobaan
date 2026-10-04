package org.example.duobaan.model.dto;

/**
 * 知识库分块（预览用），不返回向量，只返回文本与位置信息。
 */
public record ChunkDTO(
        Long id,
        Long kbId,
        int chunkIndex,
        String content,
        int charCount) {
}