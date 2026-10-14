package org.example.duobaan.model.dto;

import org.example.duobaan.model.TaskCategory;
import org.example.duobaan.model.TaskGroup;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 新增任务请求。
 *
 * 标题约束必须在入口处拦住：task.title 是 VARCHAR(200) NOT NULL，
 * 空标题 / 超长标题如果直达 DB 会抛 DataIntegrityViolationException → HTTP 500；
 * 加了 Bean Validation 后由 TaskController 上的 @Valid 提前返回 400。
 */
public record TaskRequest(
        @NotBlank(message = "任务标题不能为空")
        @Size(max = 200, message = "任务标题不能超过 200 字")
        String title,
        TaskCategory category,
        TaskGroup group,
        Integer estimatedMinutes) {
}
