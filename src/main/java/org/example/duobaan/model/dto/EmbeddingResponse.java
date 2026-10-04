package org.example.duobaan.model.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * OpenAI 兼容协议的 /embeddings 响应结构（只保留 data[].embedding）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EmbeddingResponse(List<EmbeddingData> data) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EmbeddingData(List<Double> embedding) {
    }
}