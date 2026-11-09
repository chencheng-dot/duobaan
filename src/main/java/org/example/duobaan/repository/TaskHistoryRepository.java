package org.example.duobaan.repository;

import java.util.List;

import org.example.duobaan.model.Task;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * 「我的」历史查询专用 Repository。
 *
 * <p>Task 实体标注了 {@code @SQLRestriction("deleted = 0")}，普通 TaskRepository 的派方法
 * 都会自动过滤掉软删除行。本接口刻意使用原生 SQL 绕过该过滤，因为「我的」页要展示的是
 * <b>工作留痕</b>，而不是「当前有效待办」：
 *
 * <ul>
 *   <li>已上交 / 已完成两栏<b>不按 deleted 过滤</b>：一条任务上交后被删除，它仍然是
 *       「当天上交过」的事实，从明细里消失会让上交记录凭空缩水；前端凭 DTO 的
 *       {@code deleted} 标记打「已删除」标。</li>
 *   <li>已删除栏单独查询 {@code deleted = 1}，提供彻底删除入口。</li>
 * </ul>
 *
 * <p>三栏共用 {@code LIMIT :limit} 截断；排序统一追加 {@code id DESC} 作为次序键，
 * 避免同一秒内的多条记录在两次请求之间顺序漂移。
 */
@Repository
public interface TaskHistoryRepository extends JpaRepository<Task, Long> {

    /**
     * 已上交任务：status=SUBMITTED，<b>不过滤 deleted</b>（含已被软删除的行）。
     * 按上交时间倒序；老数据可能没有 submitted_at，回退到创建时间。
     *
     * @param limit 最大返回条数，调用方保证为正数
     */
    @Query(value = """
            SELECT * FROM task
            WHERE task_status = 'SUBMITTED'
            ORDER BY COALESCE(submitted_at, created_at) DESC, id DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Task> findSubmittedTasks(@Param("limit") int limit);

    /**
     * 已完成任务：status=DONE，<b>不过滤 deleted</b>（含已被软删除的行）。
     * 按创建时间倒序。
     *
     * @param limit 最大返回条数，调用方保证为正数
     */
    @Query(value = """
            SELECT * FROM task
            WHERE task_status = 'DONE'
            ORDER BY created_at DESC, id DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Task> findDoneTasks(@Param("limit") int limit);

    /**
     * 已软删除任务：deleted=1，按删除时间倒序（最近删除的排最前）。
     *
     * @param limit 最大返回条数，调用方保证为正数
     */
    @Query(value = """
            SELECT * FROM task
            WHERE deleted = 1
            ORDER BY COALESCE(deleted_at, created_at) DESC, id DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Task> findDeletedTasks(@Param("limit") int limit);
}
