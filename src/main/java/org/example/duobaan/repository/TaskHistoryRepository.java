package org.example.duobaan.repository;

import java.util.List;

import org.example.duobaan.model.Task;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * 任务历史查询专用 Repository。
 *
 * 由于 Task 实体标注了 @SQLRestriction("deleted = 0")，普通 TaskRepository
 * 的所有方法都会自动过滤掉已软删除的行。
 * 这里使用原生 SQL 查询，绕过 Hibernate 的 @SQLRestriction 过滤条件，
 * 专门给「我的」页面提供：已上交 / 已完成 / 已删除 三类历史数据。
 */
@Repository
public interface TaskHistoryRepository extends JpaRepository<Task, Long> {

    /** 已上交任务：status=SUBMITTED，按上交时间倒序（最新上交的在最前） */
    @Query(value = """
            SELECT * FROM task
            WHERE task_status = 'SUBMITTED'
            ORDER BY COALESCE(submitted_at, created_at) DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Task> findSubmittedTasks(@Param("limit") int limit);

    /** 已完成但未删除任务：status=DONE 且 deleted=0，按创建时间倒序 */
    @Query(value = """
            SELECT * FROM task
            WHERE task_status = 'DONE' AND deleted = 0
            ORDER BY created_at DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Task> findDoneTasks(@Param("limit") int limit);

    /** 已软删除任务：deleted=1，按删除时间倒序（最近删除的在最前） */
    @Query(value = """
            SELECT * FROM task
            WHERE deleted = 1
            ORDER BY COALESCE(deleted_at, created_at) DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Task> findDeletedTasks(@Param("limit") int limit);
}
