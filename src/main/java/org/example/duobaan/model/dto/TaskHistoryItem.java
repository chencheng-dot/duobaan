package org.example.duobaan.model.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

import org.example.duobaan.model.Task;
import org.example.duobaan.model.TaskCategory;
import org.example.duobaan.model.TaskGroup;
import org.example.duobaan.model.TaskSource;
import org.example.duobaan.model.TaskStatus;

/**
 * 「我的」三栏历史中的单条任务视图（只读）。
 *
 * <p>历史查询要绕过 {@code @SQLRestriction("deleted = 0")} 才能读到软删除行，因此返回实体
 * 会把 {@code deleted}/{@code deletedAt} 这些内部字段直接暴露成接口契约的一部分：实体新增
 * 字段会静默改变前端收到的 JSON，删改字段又会悄无声息地破坏前端。改用显式 DTO 后：
 *
 * <ul>
 *   <li>「该条已删除」是写进契约的布尔标记，前端不依赖实体细节也能正确打标；</li>
 *   <li>历史是只读视图，不暴露实体上的写方法，避免被误当成可编辑对象；</li>
 *   <li>字段单位（时间、分钟数）在 DTO 层即可注释清楚，不必翻实体。</li>
 * </ul>
 *
 * <p>语义约定：{@code deleted == true} 表示该任务已被软删除，但它仍会出现在
 * 「已上交」「已完成」栏——历史是工作留痕，删除一条上交记录不应让当天的上交明细凭空少一条。
 * 「已删除」栏是独立查询，与上述两栏的标记互不替代。
 *
 * @param id               任务主键
 * @param title            任务标题
 * @param category         任务分类（CUSTOM/MEAL/WORK）
 * @param group            所属分组（TODAY/TOMORROW）
 * @param status           当前状态（TODO/DOING/DONE/SUBMITTED），历史行保留删除时的状态
 * @param source           来源（MANUAL 手动 / LLM 大模型拆单）
 * @param estimatedMinutes 预估耗时（分钟），可为 null
 * @param createdAt        创建时间
 * @param submittedAt      上交时间，仅 SUBMITTED 行有值
 * @param deleted          是否已被软删除
 * @param deletedAt        软删除时间，deleted=false 时为 null
 */
public record TaskHistoryItem(
        Long id,
        String title,
        TaskCategory category,
        TaskGroup group,
        TaskStatus status,
        TaskSource source,
        Integer estimatedMinutes,
        LocalDateTime createdAt,
        LocalDateTime submittedAt,
        boolean deleted,
        LocalDateTime deletedAt
) {

    /**
     * 实体转历史视图。
     *
     * <p>{@code deleted} 在库中是 NOT NULL DEFAULT 0，但原生 SQL 绕过实体映射、
     * 读到 NULL 时 {@link Boolean} 可能为 null，这里统一按「未删除」处理，避免拆箱 NPE。
     *
     * @param task 任务实体，不允许为 null
     * @return 只读历史视图
     * @throws NullPointerException task 为 null 时抛出（属于编程错误，不是业务异常）
     */
    public static TaskHistoryItem from(Task task) {
        Objects.requireNonNull(task, "task 不能为 null");
        return new TaskHistoryItem(
                task.getId(),
                task.getTitle(),
                task.getCategory(),
                task.getGroup(),
                task.getStatus(),
                task.getSource(),
                task.getEstimatedMinutes(),
                task.getCreatedAt(),
                task.getSubmittedAt(),
                Boolean.TRUE.equals(task.getDeleted()),
                task.getDeletedAt());
    }

    /**
     * 批量映射，容忍 null 列表与 null 元素：历史页是只读展示，单条脏数据不应让整页 500。
     *
     * @param tasks 实体列表，可为 null
     * @return 不可变视图列表，永不为 null
     */
    public static List<TaskHistoryItem> fromAll(List<Task> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return List.of();
        }
        return tasks.stream()
                .filter(Objects::nonNull)
                .map(TaskHistoryItem::from)
                .toList();
    }
}
