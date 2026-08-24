package org.example.duobaan.model.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * OpenAI 兼容协议的请求与响应结构。
 */
public record LlmChat() {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Request(String model, List<Message> messages, boolean stream) {
        public Request(String model, List<Message> messages) {
            this(model, messages, false);
        }
    }

    public record Message(String role, String content) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Response(List<Choice> choices) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Choice(Message message) {
    }
}
