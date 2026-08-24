package org.example.duobaan.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.example.duobaan.model.Task;
import org.example.duobaan.model.TaskCategory;
import org.example.duobaan.model.TaskGroup;
import org.example.duobaan.model.TaskSource;
import org.example.duobaan.model.TaskStatus;
import org.example.duobaan.model.dto.TaskPatch;
import org.example.duobaan.model.dto.TaskRequest;
import org.example.duobaan.model.dto.TaskSummary;
import org.example.duobaan.repository.TaskRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 流程表服务：CRUD、状态流转、今日↔明日迁移、上交小结。
 */
@Service
public class TaskService {

    private final TaskRepository repo;

    public TaskService(TaskRepository repo) {
        this.repo = repo;
    }

    public List<Task> list(TaskGroup group) {
        return repo.findByGroupOrderByCreatedAtAsc(group);
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
            TaskCategory category = item.category() != null ? item.category() : TaskCategory.WORK;
            Task t = new Task(item.title(), category, normalizeGroup(item.group()));
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
        for (Task t : today) {
            if (t.getStatus() == TaskStatus.DONE) {
                t.setStatus(TaskStatus.SUBMITTED);
                submitted.add(t);
            } else {
                remaining.add(t);
            }
        }
        repo.saveAll(submitted);
        return new TaskSummary(submitted.size(), remaining.size(), submitted, remaining);
    }

    /** 删除任务 */
    @Transactional
    public void delete(Long id) {
        if (!repo.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "任务不存在: " + id);
        }
        repo.deleteById(id);
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
}
