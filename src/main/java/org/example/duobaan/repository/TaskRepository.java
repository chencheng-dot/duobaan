package org.example.duobaan.repository;

import java.util.List;

import org.example.duobaan.model.Task;
import org.example.duobaan.model.TaskGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface TaskRepository extends JpaRepository<Task, Long> {

    List<Task> findByGroupOrderByCreatedAtAsc(TaskGroup group);

    /** 流程表：按分组取未完成状态（排除 DONE/SUBMITTED），交由数据库过滤而非内存 */
    @Query("SELECT t FROM Task t WHERE t.group = :group "
            + "AND t.status NOT IN (org.example.duobaan.model.TaskStatus.DONE, org.example.duobaan.model.TaskStatus.SUBMITTED) "
            + "ORDER BY t.createdAt ASC")
    List<Task> findActiveByGroup(@Param("group") TaskGroup group);

    /**
     * 零点转组：把所有「明日」任务原子地改为「今日」，返回受影响行数。
     *
     * 用单条 native UPDATE 取代「查出实体 → setGroup → saveAll」的逐条写法：
     *  - 整批在一个事务里一次提交，不会再出现「转了一半」的中间态；
     *  - 由数据库判定 WHERE 条件，多实例同时执行时第二条只会影响 0 行，天然幂等；
     *  - 显式写 deleted = 0，因为 native SQL 不会自动带上实体上的 @SQLRestriction。
     *
     * 与原 saveAll 逻辑逐字段核对：原逻辑除 group 外没有改任何字段，所以「行为等价」。
     * clearAutomatically/flushAutomatically 用于丢弃批量更新后可能已过期的持久化上下文缓存。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE task SET task_group = 'TODAY' WHERE task_group = 'TOMORROW' AND deleted = 0", nativeQuery = true)
    int moveAllTomorrowToToday();

    /**
     * 物理删除（硬删除）：绕过实体上的 @SQLDelete（软删除），直接 DELETE 行。
     * 供「我的 → 已删除」中的彻底删除使用。
     * 仅允许删除已软删除(deleted=1)的行，避免越过软删除保护物理删除可用任务。
     */
    @Modifying
    @Query(value = "DELETE FROM task WHERE id = :id AND deleted = 1", nativeQuery = true)
    int hardDelete(@Param("id") Long id);
}
