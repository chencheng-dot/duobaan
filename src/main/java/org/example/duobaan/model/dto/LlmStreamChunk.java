package org.example.duobaan.model.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * OpenAI 兼容协议的流式分片（仅保留增量内容字段）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LlmStreamChunk(List<Choice> choices) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Choice(Delta delta) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Delta(String content) {
    }
}
