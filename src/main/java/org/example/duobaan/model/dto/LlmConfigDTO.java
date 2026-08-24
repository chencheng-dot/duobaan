package org.example.duobaan.model.dto;

/**
 * 大模型配置 DTO：前后端传输用。
 * provider 预设：CHAIGPT / DEEPSEEK / DOUBAO / QIANWEN / CUSTOM
 */
public record LlmConfigDTO(
        String provider,
        String baseUrl,
        String apiKey,
        String model,
        int timeoutSeconds) {
}
