package org.example.duobaan.model.dto;

import org.example.duobaan.model.TaskGroup;
import org.example.duobaan.model.TaskStatus;

import jakarta.validation.constraints.Size;

/**
 * 任务更新请求：状态与分组迁移。
 *
 * title 可以为 null（表示「本次不修改标题」，TaskService.patch 里判空跳过），
 * @Size 对 null 不做校验，因此只约束「传了就不得超过 200 字」。
 */
public record TaskPatch(
        TaskStatus status,
        TaskGroup group,
        @Size(max = 200, message = "任务标题不能超过 200 字")
        String title) {
}
