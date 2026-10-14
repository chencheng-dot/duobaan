package org.example.duobaan.service;

import org.example.duobaan.repository.TaskRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 零点转组服务：把「明日」分组的任务整批转到「今日」。
 *
 * 为什么单独抽一个 Bean（而不是留在 TaskService 里）：
 *  - TaskService.list() / contextSummary() 是<b>非事务</b>的查询入口，原来它们内部直接
 *    调 repo.saveAll()，Spring 的 @Transactional 代理无法作用于「this 内部调用」，
 *    导致 saveAll 里的每条 UPDATE 各自独立提交 —— 中途失败就会留下「转了一半」的数据。
 *  - 抽成独立 Bean 后，定时任务和懒加载查询都是<b>跨 Bean 调用</b>，@Transactional 代理真正生效，
 *    整个转组只有一个事务、一次提交：要么全转，要么全不转。
 *  - 方法返回即代表事务已提交，调用方只有在拿到返回值后才能认为「今天转组成功」。
 */
@Service
public class TaskRolloverService {

    private final TaskRepository repo;

    public TaskRolloverService(TaskRepository repo) {
        this.repo = repo;
    }

    /**
     * 原子转组：一条 SQL 把 task_group 从 TOMORROW 改成 TODAY。
     *
     * 等价性说明（对照改造前 TaskService 里 findTomorrow + setGroup + saveAll 的写法）：
     * 原逻辑只修改了 group 字段（status / submittedAt / 时间戳等一律不动），
     * 且 find 走的是实体的 @SQLRestriction("deleted = 0")，故 native SQL 里显式加了 deleted = 0。
     *
     * 原子性：整批更新在单个数据库事务内完成并提交，外键/约束失败时全部回滚；
     * 并发（多实例同时触发）时由 MySQL 行锁串行化，后执行者匹配 0 行，结果一致且幂等。
     *
     * @return 本次实际转组的任务条数（0 表示没有明日任务，或已被其他实例转完）
     */
    @Transactional
    public int doRollover() {
        return repo.moveAllTomorrowToToday();
    }
}
