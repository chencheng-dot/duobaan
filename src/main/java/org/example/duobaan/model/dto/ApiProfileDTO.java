package org.example.duobaan.model.dto;

import java.time.LocalDateTime;

import org.example.duobaan.model.ApiProfileType;

/**
 * 对外返回的 API Profile（打码版，绝不包含明文 apiKey）。
 */
public record ApiProfileDTO(
        Long id,
        ApiProfileType profileType,
        String name,
        String provider,
        String baseUrl,
        String model,
        String apiKeyMasked,     // 只含前4****后4或****
        String location,
        Long cacheTtlSeconds,
        Integer timeoutSeconds,
        boolean isActive,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
