package org.example.duobaan.model.dto;

import org.example.duobaan.model.ChatMode;

/**
 * 前端对话请求：单轮消息 + 模式。
 */
public record ChatRequest(
        String message,
        ChatMode mode) {
}
