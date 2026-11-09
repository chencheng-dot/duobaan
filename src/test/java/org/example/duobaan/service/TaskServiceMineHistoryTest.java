package org.example.duobaan.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import org.example.duobaan.model.Task;
import org.example.duobaan.model.TaskCategory;
import org.example.duobaan.model.TaskGroup;
import org.example.duobaan.model.TaskStatus;
import org.example.duobaan.model.dto.MineTasksResponse;
import org.example.duobaan.repository.TaskHistoryRepository;
import org.example.duobaan.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link TaskService#mineHistory()} 的行为测试。
 *
 * <p>锁定 O3(c) 的核心约定：历史查询<b>不做软删除过滤</b>，已删除任务仍进入
 * 「已上交 / 已完成」两栏，并带着 {@code deleted=true} 的标记交给前端；
 * 「已删除」栏是独立查询，三栏互不替代。同时覆盖空结果与调用参数（每栏一次、上限一致）。
 */
class TaskServiceMineHistoryTest {

    private TaskHistoryRepository historyRepo;
    private TaskService service;

    @BeforeEach
    void setUp() {
        historyRepo = mock(TaskHistoryRepository.class);
        service = new TaskService(mock(TaskRepository.class), historyRepo, mock(TaskRolloverService.class));
    }

    private static Task task(long id, String title, TaskStatus status, boolean deleted) {
        Task t = new Task(title, TaskCategory.WORK, TaskGroup.TODAY);
        t.setId(id);
        t.setStatus(status);
        t.setCreatedAt(LocalDateTime.of(2026, 11, 9, 9, 0).plusMinutes(id));
        t.setDeleted(deleted);
        if (deleted) {
            t.setDeletedAt(LocalDateTime.of(2026, 11, 9, 20, 0).plusMinutes(id));
        }
        return t;
    }

    @Test
    void keepsDeletedTasksInSubmittedAndDoneColumns() {
        Task submittedDeleted = task(1L, "上交后被删", TaskStatus.SUBMITTED, true);
        Task submittedLive = task(2L, "正常上交", TaskStatus.SUBMITTED, false);
        Task doneDeleted = task(3L, "完成后被删", TaskStatus.DONE, true);
        when(historyRepo.findSubmittedTasks(TaskService.HISTORY_LIMIT))
                .thenReturn(List.of(submittedDeleted, submittedLive));
        when(historyRepo.findDoneTasks(TaskService.HISTORY_LIMIT))
                .thenReturn(List.of(doneDeleted));
        when(historyRepo.findDeletedTasks(TaskService.HISTORY_LIMIT))
                .thenReturn(List.of(submittedDeleted, doneDeleted));

        MineTasksResponse resp = service.mineHistory();

        assertEquals(2, resp.submitted().size(), "已删除的上交记录仍须留在「已上交」栏");
        assertTrue(resp.submitted().get(0).deleted());
        assertEquals(1L, resp.submitted().get(0).id());
        assertFalse(resp.submitted().get(1).deleted());
        assertEquals(1, resp.done().size(), "已删除的完成记录仍须留在「已完成」栏");
        assertTrue(resp.done().get(0).deleted());
        assertEquals(2, resp.deleted().size(), "「已删除」栏仍是独立查询");
    }

    @Test
    void preservesRepositoryOrderWithoutReSorting() {
        // 排序是 SQL 的职责（含 id DESC 次序键），服务层不得二次排序打乱分页/截断语义
        Task newest = task(30L, "最新", TaskStatus.SUBMITTED, false);
        Task older = task(10L, "较早", TaskStatus.SUBMITTED, false);
        when(historyRepo.findSubmittedTasks(TaskService.HISTORY_LIMIT)).thenReturn(List.of(newest, older));
        when(historyRepo.findDoneTasks(TaskService.HISTORY_LIMIT)).thenReturn(List.of());
        when(historyRepo.findDeletedTasks(TaskService.HISTORY_LIMIT)).thenReturn(List.of());

        MineTasksResponse resp = service.mineHistory();

        assertEquals(List.of(30L, 10L), resp.submitted().stream().map(i -> i.id()).toList());
    }

    @Test
    void returnsEmptyColumnsWhenRepositoryReturnsNull() {
        // 防御性兜底：仓储实现异常/代理返回 null 时，接口应是空列表而不是 500
        when(historyRepo.findSubmittedTasks(TaskService.HISTORY_LIMIT)).thenReturn(null);
        when(historyRepo.findDoneTasks(TaskService.HISTORY_LIMIT)).thenReturn(null);
        when(historyRepo.findDeletedTasks(TaskService.HISTORY_LIMIT)).thenReturn(null);

        MineTasksResponse resp = service.mineHistory();

        assertTrue(resp.submitted().isEmpty());
        assertTrue(resp.done().isEmpty());
        assertTrue(resp.deleted().isEmpty());
    }

    @Test
    void queriesEachColumnOnceWithSharedLimit() {
        when(historyRepo.findSubmittedTasks(TaskService.HISTORY_LIMIT)).thenReturn(List.of());
        when(historyRepo.findDoneTasks(TaskService.HISTORY_LIMIT)).thenReturn(List.of());
        when(historyRepo.findDeletedTasks(TaskService.HISTORY_LIMIT)).thenReturn(List.of());

        service.mineHistory();

        verify(historyRepo).findSubmittedTasks(TaskService.HISTORY_LIMIT);
        verify(historyRepo).findDoneTasks(TaskService.HISTORY_LIMIT);
        verify(historyRepo).findDeletedTasks(TaskService.HISTORY_LIMIT);
    }
}
