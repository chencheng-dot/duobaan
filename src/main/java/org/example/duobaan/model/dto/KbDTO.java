package org.example.duobaan.model.dto;

import java.time.LocalDateTime;

/**
 * 知识库概要信息（含分块数 + 分块配置），用于「我的」页知识库列表。
 */
public record KbDTO(
        Long id,
        String name,
        String description,
        int chunkCount,
        int chunkSize,
        int chunkOverlap,
        String splitStrategy,
        LocalDateTime createdAt) {
}
