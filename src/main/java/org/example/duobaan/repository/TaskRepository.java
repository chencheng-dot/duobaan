package org.example.duobaan.repository;

import java.util.List;

import org.example.duobaan.model.Task;
import org.example.duobaan.model.TaskGroup;
import org.example.duobaan.model.TaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface TaskRepository extends JpaRepository<Task, Long> {

    List<Task> findByGroupOrderByCreatedAtAsc(TaskGroup group);

    long countByGroupAndStatus(TaskGroup group, TaskStatus status);

    /**
     * 物理删除（硬删除）：绕过实体上的 @SQLDelete（软删除），直接 DELETE 行。
     * 供「我的 → 已删除」中的彻底删除使用。
     */
    @Modifying
    @Query(value = "DELETE FROM task WHERE id = :id", nativeQuery = true)
    int hardDelete(@Param("id") Long id);
}
