package org.example.duobaan.model.dto;

import org.example.duobaan.model.TaskCategory;
import org.example.duobaan.model.TaskGroup;

/**
 * 新增任务请求。
 */
public record TaskRequest(
        String title,
        TaskCategory category,
        TaskGroup group,
        Integer estimatedMinutes) {
}
