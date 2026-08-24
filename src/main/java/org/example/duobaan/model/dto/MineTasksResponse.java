package org.example.duobaan.model.dto;

import java.util.List;

import org.example.duobaan.model.Task;

/**
 * 「我的」Tab 三栏历史响应：已上交 / 已完成 / 已删除。
 *
 * @param submitted 已上交的任务（status=SUBMITTED），按上交时间倒序
 * @param done      已完成的任务（status=DONE，未删除），按创建时间倒序
 * @param deleted   已删除的任务（deleted=1），按删除时间倒序
 */
public record MineTasksResponse(
        List<Task> submitted,
        List<Task> done,
        List<Task> deleted
) {}
