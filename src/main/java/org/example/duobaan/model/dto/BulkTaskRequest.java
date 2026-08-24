package org.example.duobaan.model.dto;

import java.util.List;

import org.example.duobaan.model.TaskCategory;

/**
 * 批量建任务请求：大模型拆单结果一键写入流程表。
 */
public record BulkTaskRequest(List<Item> tasks) {

    public record Item(String title, String group, TaskCategory category) {
    }
}
