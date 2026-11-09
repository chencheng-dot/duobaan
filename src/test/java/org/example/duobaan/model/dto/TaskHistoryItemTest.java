package org.example.duobaan.model.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.example.duobaan.model.Task;
import org.example.duobaan.model.TaskCategory;
import org.example.duobaan.model.TaskGroup;
import org.example.duobaan.model.TaskSource;
import org.example.duobaan.model.TaskStatus;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

/**
 * {@link TaskHistoryItem} 的映射与接口契约测试。
 *
 * <p>背景：O3(c) 让「已上交 / 已完成」两栏不再过滤 {@code deleted = 0}，已删除任务会进入这两栏。
 * 如果接口不暴露 {@code deleted}，前端就无法把它们和有效记录区分开，删除的历史会被当成
 * 正常任务展示（甚至提供无效操作）。这里同时锁住「映射不丢标记」与「JSON 里真的有这个字段」。
 */
class TaskHistoryItemTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static Task task(long id, String title, TaskStatus status, boolean deleted) {
        Task t = new Task(title, TaskCategory.WORK, TaskGroup.TODAY);
        t.setId(id);
        t.setStatus(status);
        t.setSource(TaskSource.LLM);
        t.setEstimatedMinutes(25);
        t.setCreatedAt(LocalDateTime.of(2026, 11, 9, 9, 0));
        t.setSubmittedAt(LocalDateTime.of(2026, 11, 9, 18, 30));
        t.setDeleted(deleted);
        if (deleted) {
            t.setDeletedAt(LocalDateTime.of(2026, 11, 9, 20, 15));
        }
        return t;
    }

    @Test
    void mapsAllFieldsIncludingDeletedFlag() {
        TaskHistoryItem item = TaskHistoryItem.from(task(42L, "写周报", TaskStatus.SUBMITTED, true));

        assertEquals(42L, item.id());
        assertEquals("写周报", item.title());
        assertEquals(TaskCategory.WORK, item.category());
        assertEquals(TaskGroup.TODAY, item.group());
        assertEquals(TaskStatus.SUBMITTED, item.status());
        assertEquals(TaskSource.LLM, item.source());
        assertEquals(25, item.estimatedMinutes());
        assertEquals(LocalDateTime.of(2026, 11, 9, 9, 0), item.createdAt());
        assertEquals(LocalDateTime.of(2026, 11, 9, 18, 30), item.submittedAt());
        assertTrue(item.deleted());
        assertEquals(LocalDateTime.of(2026, 11, 9, 20, 15), item.deletedAt());
    }

    @Test
    void keepsNullDeletedAtForLiveTask() {
        TaskHistoryItem item = TaskHistoryItem.from(task(7L, "喝水", TaskStatus.DONE, false));

        assertFalse(item.deleted());
        assertNull(item.deletedAt());
    }

    @Test
    void treatsNullDeletedColumnAsNotDeleted() {
        // 原生 SQL 绕过实体映射，理论上可能读到 NULL；这里必须按「未删除」处理而不是抛 NPE
        Task t = task(8L, "旧数据", TaskStatus.DONE, false);
        t.setDeleted(null);

        assertFalse(TaskHistoryItem.from(t).deleted());
    }

    @Test
    void fromRejectsNullTask() {
        assertThrows(NullPointerException.class, () -> TaskHistoryItem.from(null));
    }

    @Test
    void fromAllToleratesNullListAndNullElements() {
        assertTrue(TaskHistoryItem.fromAll(null).isEmpty());
        assertTrue(TaskHistoryItem.fromAll(List.of()).isEmpty());

        List<Task> dirty = new ArrayList<>(Arrays.asList(task(1L, "A", TaskStatus.DONE, false), null));
        List<TaskHistoryItem> mapped = TaskHistoryItem.fromAll(dirty);

        assertEquals(1, mapped.size());
        assertEquals(1L, mapped.get(0).id());
    }

    @Test
    void jsonContractExposesDeletedFlag() throws Exception {
        // 前端靠这个字段打「已删除」标；字段一旦被改名或漏序列化，历史页会静默退化
        TaskHistoryItem item = TaskHistoryItem.from(task(9L, "被删掉的上交", TaskStatus.SUBMITTED, true));
        String json = objectMapper.writeValueAsString(item);

        assertTrue(json.contains("\"deleted\":true"), "JSON 必须包含 deleted=true，实际：" + json);
        assertTrue(json.contains("\"deletedAt\""), "JSON 必须包含 deletedAt，实际：" + json);
    }
}
