package org.example.duobaan.model.dto;

/**
 * 大模型拆单结果：单条任务（标题 + 分组）。
 */
public record ParsedTask(String title, String group) {
}
