package org.example.duobaan.model.dto;

/**
 * 拆单请求：用户指令 + 默认分组。
 */
public record ParseTasksRequest(String message, String group) {
}
