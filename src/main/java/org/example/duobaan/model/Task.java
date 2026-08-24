package org.example.duobaan.model;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

/**
 * 流程表条目：今日/明日分组，状态流转，可上交。
 *
 * 软删除策略（工作留痕）：
 * - 调用 Repository.deleteById() 时，Hibernate 执行 @SQLDelete 自定义 SQL
 *   （UPDATE SET deleted=1, deleted_at=NOW()），不会物理删除行。
 * - 正常列表/查询通过 @SQLRestriction 自动过滤掉 deleted=1 的行，
 *   只有专门的"历史查询"接口会绕过此过滤（使用 nativeQuery 的新 Repository）。
 */
@Entity
@Table(name = "task")
@SQLRestriction("deleted = 0")
@SQLDelete(sql = "UPDATE task SET deleted = 1, deleted_at = NOW(6) WHERE id = ?")
public class Task {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TaskCategory category = TaskCategory.CUSTOM;

    @Enumerated(EnumType.STRING)
    @Column(name = "task_group", nullable = false, length = 20)
    private TaskGroup group = TaskGroup.TODAY;

    @Enumerated(EnumType.STRING)
    @Column(name = "task_status", nullable = false, length = 20)
    private TaskStatus status = TaskStatus.TODO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TaskSource source = TaskSource.MANUAL;

    private Integer estimatedMinutes;

    private LocalDateTime dueAt;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    /** 上交时间：任务被标记为 SUBMITTED 时写入，用于"我已上交"工作留痕排序 */
    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    /** 软删除标记：0=正常，1=已删除。默认 0，实体查询自动过滤 deleted=1 */
    @Column(columnDefinition = "TINYINT(1) DEFAULT 0")
    private Boolean deleted = false;

    /** 软删除时间：deleted=1 时写入，用于"已删除"列表按删除时间倒序 */
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    public Task() {
    }

    public Task(String title, TaskCategory category, TaskGroup group) {
        this.title = title;
        this.category = category;
        this.group = group;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public TaskCategory getCategory() {
        return category;
    }

    public void setCategory(TaskCategory category) {
        this.category = category;
    }

    public TaskGroup getGroup() {
        return group;
    }

    public void setGroup(TaskGroup group) {
        this.group = group;
    }

    public TaskStatus getStatus() {
        return status;
    }

    public void setStatus(TaskStatus status) {
        this.status = status;
    }

    public TaskSource getSource() {
        return source;
    }

    public void setSource(TaskSource source) {
        this.source = source;
    }

    public Integer getEstimatedMinutes() {
        return estimatedMinutes;
    }

    public void setEstimatedMinutes(Integer estimatedMinutes) {
        this.estimatedMinutes = estimatedMinutes;
    }

    public LocalDateTime getDueAt() {
        return dueAt;
    }

    public void setDueAt(LocalDateTime dueAt) {
        this.dueAt = dueAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getSubmittedAt() {
        return submittedAt;
    }

    public void setSubmittedAt(LocalDateTime submittedAt) {
        this.submittedAt = submittedAt;
    }

    public Boolean getDeleted() {
        return deleted;
    }

    public void setDeleted(Boolean deleted) {
        this.deleted = deleted;
    }

    public LocalDateTime getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(LocalDateTime deletedAt) {
        this.deletedAt = deletedAt;
    }
}
