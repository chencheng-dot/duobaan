package org.example.duobaan.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.example.duobaan.model.Task;
import org.example.duobaan.model.TaskCategory;
import org.example.duobaan.model.TaskGroup;
import org.example.duobaan.model.TaskSource;
import org.example.duobaan.model.TaskStatus;
import org.example.duobaan.model.dto.MineTasksResponse;
import org.example.duobaan.model.dto.TaskPatch;
import org.example.duobaan.model.dto.TaskRequest;
import org.example.duobaan.model.dto.TaskSummary;
import org.example.duobaan.repository.TaskHistoryRepository;
import org.example.duobaan.repository.TaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 流程表服务：CRUD、状态流转、今日↔明日迁移、上交小结。
 *
 * 零点自动转组策略：
 *  - 每天 00:00 通过 @Scheduled 把所有 TOMORROW 组任务转回 TODAY
 *  - 同时在 list() / contextSummary() 等查询入口懒加载触发，防止夜间停机错过零点
 */
@Service
public class TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);

    private static final int HISTORY_LIMIT = 500;

    /** 标题长度上限，与 task.title VARCHAR(200) 及 TaskRequest 上的 @Size 保持一致 */
    private static final int TITLE_MAX_LENGTH = 200;

    private final TaskRepository repo;
    private final TaskHistoryRepository historyRepo;
    private final TaskRolloverService rolloverService;

    public TaskService(TaskRepository repo, TaskHistoryRepository historyRepo,
            TaskRolloverService rolloverService) {
        this.repo = repo;
        this.historyRepo = historyRepo;
        this.rolloverService = rolloverService;
    }

    /** 上一次<b>成功</b>执行 rollover 的日期；失败时保持旧值，允许后续查询重试 */
    private volatile LocalDate lastRolloverDate;

    /**
     * 零点转组：把「明日」分组的任务全部转到「今日」。
     * 每天凌晨 0:05 执行（延迟 5 秒避免整点竞争）。
     *
     * 这里刻意<b>不加 @Transactional</b>：真正的转组在 TaskRolloverService.doRollover() 里，
     * 它自己开事务。如果本方法再包一层事务，提交会推迟到本方法返回之后 ——
     * 那就可能在事务尚未提交时就乐观地推进 lastRolloverDate。
     * 在外层无事务时，doRollover() 的事务是最外层，返回即代表已提交，可以安全置位。
     */
    @Scheduled(cron = "0 5 0 * * *")
    public void rolloverTomorrowToToday() {
        runRollover("定时任务");
    }

    /**
     * 懒加载转组：每次查询前调用，确保即使夜间停机，重启后也能立即完成转组。
     * 仅当日历日变化时触发。
     */
    private void ensureRollover() {
        runRollover("懒加载查询");
    }

    /**
     * 转组的唯一入口：定时与懒加载共用同一段逻辑（对外仍是「定时 + 懒加载双保险」）。
     *
     * 关键点：<b>先干活，成功后置位</b>。旧的写法是先把 lastRolloverDate 置为今天再 saveAll，
     * 一旦 saveAll 抛异常，当天就再也不会重试（因为日期看起来已经「转过了」）。
     * 现在只有 doRollover() 正常返回（事务已提交）才推进日期；失败则只 warn，日期保持旧值，
     * 下一次 list() / contextSummary() 会自然重试，无需人工干预。
     *
     * 多实例：lastRolloverDate 是进程内内存态，多个实例会各自触发一次；
     * 但 doRollover() 是单条带 WHERE 条件的原子 UPDATE，第二个实例只会匹配 0 行，结果幂等。
     */
    private void runRollover(String trigger) {
        LocalDate today = LocalDate.now();
        if (today.equals(lastRolloverDate)) {
            return;
        }
        try {
            int moved = rolloverService.doRollover();
            lastRolloverDate = today;
            if (moved > 0) {
                log.info("[TaskRollover] {} 触发：{} 条「明日」任务已转为「今日」", trigger, moved);
            }
        } catch (Exception e) {
            // 不推进 lastRolloverDate，允许下次查询重试
            log.warn("[TaskRollover] {} 触发转组失败，保留重试机会（{}）：{}",
                    trigger, today, e.getMessage());
        }
    }

    public List<Task> list(TaskGroup group) {
        ensureRollover();
        // 流程表只显示待办/进行中；已完成(DONE)和已上交(SUBMITTED)的任务只在「我的」页可见
        return repo.findActiveByGroup(group);
    }

    @Transactional
    public Task create(TaskRequest req) {
        Task t = new Task(
                req.title(),
                req.category() != null ? req.category() : org.example.duobaan.model.TaskCategory.CUSTOM,
                req.group() != null ? req.group() : TaskGroup.TODAY);
        t.setEstimatedMinutes(req.estimatedMinutes());
        t.setSource(TaskSource.MANUAL);
        return repo.save(t);
    }

    /** 大模型生成的任务直接入库 */
    @Transactional
    public Task createFromLlm(String title, TaskGroup group) {
        Task t = new Task(title, org.example.duobaan.model.TaskCategory.WORK, group);
        t.setSource(TaskSource.LLM);
        return repo.save(t);
    }

    /** 批量建任务：大模型拆单结果一键写入流程表 */
    @Transactional
    public List<Task> bulkCreate(List<org.example.duobaan.model.dto.BulkTaskRequest.Item> items) {
        List<Task> created = new ArrayList<>();
        for (org.example.duobaan.model.dto.BulkTaskRequest.Item item : items) {
            // BulkTaskRequest.Item 是嵌套 record、不在本次允许改动的文件清单内，
            // 无法在它上面加 @NotBlank/@Size 让 @Valid 级联生效，所以在服务层显式兜底校验，
            // 避免空标题 / 超长标题直接落到 DB 抛 DataIntegrityViolationException（500）。
            String title = item.title();
            if (title == null || title.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "任务标题不能为空");
            }
            if (title.length() > TITLE_MAX_LENGTH) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "任务标题不能超过 " + TITLE_MAX_LENGTH + " 字");
            }
            TaskCategory category = item.category() != null ? item.category() : TaskCategory.WORK;
            Task t = new Task(title, category, normalizeGroup(item.group()));
            t.setSource(TaskSource.LLM);
            created.add(repo.save(t));
        }
        return created;
    }

    private TaskGroup normalizeGroup(String groupStr) {
        if (groupStr == null) {
            return TaskGroup.TODAY;
        }
        try {
            return TaskGroup.valueOf(groupStr.trim().toUpperCase());
        } catch (Exception e) {
            return TaskGroup.TODAY;
        }
    }

    @Transactional
    public Task patch(Long id, TaskPatch patch) {
        Task t = findOrThrow(id);
        if (patch.status() != null) {
            t.setStatus(patch.status());
        }
        if (patch.group() != null) {
            t.setGroup(patch.group());
        }
        if (patch.title() != null && !patch.title().isBlank()) {
            t.setTitle(patch.title());
        }
        return repo.save(t);
    }

    /** 迁移到指定分组（今日↔明日） */
    @Transactional
    public Task migrate(Long id, TaskGroup target) {
        Task t = findOrThrow(id);
        t.setGroup(target);
        return repo.save(t);
    }

    /** 上交今日小结：把今日已完成项标记为已上交，返回小结 */
    @Transactional
    public TaskSummary submit() {
        List<Task> today = repo.findByGroupOrderByCreatedAtAsc(TaskGroup.TODAY);
        List<Task> submitted = new ArrayList<>();
        List<Task> remaining = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (Task t : today) {
            if (t.getStatus() == TaskStatus.DONE) {
                t.setStatus(TaskStatus.SUBMITTED);
                t.setSubmittedAt(now);
                submitted.add(t);
            } else {
                remaining.add(t);
            }
        }
        repo.saveAll(submitted);
        return new TaskSummary(submitted.size(), remaining.size(), submitted, remaining);
    }

    /** 删除任务（软删除：标记 deleted=1，保留在「我的 → 已删除」留痕） */
    @Transactional
    public void delete(Long id) {
        if (!repo.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "任务不存在: " + id);
        }
        repo.deleteById(id);
    }

    /** 硬删除：物理删除行，不可恢复。仅允许删除已软删除(deleted=1)的行。 */
    @Transactional
    public void hardDelete(Long id) {
        int rows = repo.hardDelete(id);
        if (rows == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "只能彻底删除「已删除」列表中的任务（id=" + id + "）");
        }
    }

    public Task get(Long id) {
        return findOrThrow(id);
    }

    private Task findOrThrow(Long id) {
        Optional<Task> t = repo.findById(id);
        return t.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "任务不存在: " + id));
    }

    /** 供大模型上下文用的流程表摘要 */
    public String contextSummary() {
        ensureRollover();
        List<Task> today = repo.findByGroupOrderByCreatedAtAsc(TaskGroup.TODAY);
        List<Task> tomorrow = repo.findByGroupOrderByCreatedAtAsc(TaskGroup.TOMORROW);
        StringBuilder sb = new StringBuilder();
        sb.append("今日任务：");
        if (today.isEmpty()) {
            sb.append("无");
        } else {
            for (Task t : today) {
                sb.append("\n- ").append(t.getTitle()).append("[").append(t.getStatus()).append("]");
            }
        }
        sb.append("\n明日任务：");
        if (tomorrow.isEmpty()) {
            sb.append("无");
        } else {
            for (Task t : tomorrow) {
                sb.append("\n- ").append(t.getTitle()).append("[").append(t.getStatus()).append("]");
            }
        }
        return sb.toString();
    }

    /**
     * 「我的」Tab 三栏历史：已上交 / 已完成 / 已删除。
     * 通过 historyRepo 原生 SQL 绕过 @SQLRestriction，能看到软删除的行。
     */
    public MineTasksResponse mineHistory() {
        return new MineTasksResponse(
                historyRepo.findSubmittedTasks(HISTORY_LIMIT),
                historyRepo.findDoneTasks(HISTORY_LIMIT),
                historyRepo.findDeletedTasks(HISTORY_LIMIT)
        );
    }
}
