package org.example.duobaan.model.dto;

import java.util.List;

/**
 * 「我的」Tab 三栏历史响应：已上交 / 已完成 / 已删除。
 *
 * <p>三栏都只返回只读视图 {@link TaskHistoryItem}，其中 {@code deleted} 标记用来区分
 * 「当时上交过、后来被删掉」的任务——这类任务会同时出现在「已上交」与「已删除」两栏，
 * 前者保留工作留痕，后者提供彻底删除入口。
 *
 * @param submitted 已上交的任务（status=SUBMITTED，含已被软删除的行），按上交时间倒序
 * @param done      已完成的任务（status=DONE，含已被软删除的行），按创建时间倒序
 * @param deleted   已删除的任务（deleted=1），按删除时间倒序
 */
public record MineTasksResponse(
        List<TaskHistoryItem> submitted,
        List<TaskHistoryItem> done,
        List<TaskHistoryItem> deleted
) {}
