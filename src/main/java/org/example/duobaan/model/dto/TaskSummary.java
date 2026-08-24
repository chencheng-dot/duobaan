package org.example.duobaan.model.dto;

import java.util.List;

import org.example.duobaan.model.Task;
import org.example.duobaan.model.TaskGroup;

/**
 * 上交今日小结的结果：已上交条目数 + 剩余待办。
 */
public record TaskSummary(
        int submittedCount,
        int remainingCount,
        List<Task> submitted,
        List<Task> remaining) {

    public static TaskGroup defaultGroup() {
        return TaskGroup.TODAY;
    }
}
