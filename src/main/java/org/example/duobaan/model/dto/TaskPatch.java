package org.example.duobaan.model.dto;

import org.example.duobaan.model.TaskGroup;
import org.example.duobaan.model.TaskStatus;

/**
 * 任务更新请求：状态与分组迁移。
 */
public record TaskPatch(
        TaskStatus status,
        TaskGroup group,
        String title) {
}
