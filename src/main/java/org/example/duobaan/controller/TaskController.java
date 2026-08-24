package org.example.duobaan.controller;

import java.util.List;

import org.example.duobaan.model.Task;
import org.example.duobaan.model.TaskGroup;
import org.example.duobaan.model.dto.BulkTaskRequest;
import org.example.duobaan.model.dto.TaskPatch;
import org.example.duobaan.model.dto.TaskRequest;
import org.example.duobaan.model.dto.TaskSummary;
import org.example.duobaan.service.TaskService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 流程表接口：今日/明日分组、状态流转、迁移、上交小结。
 */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @GetMapping
    public List<Task> list(@RequestParam TaskGroup group) {
        return taskService.list(group);
    }

    @PostMapping
    public Task create(@RequestBody TaskRequest req) {
        return taskService.create(req);
    }

    /** 批量建任务：大模型拆单结果一键写入流程表 */
    @PostMapping("/bulk")
    public List<Task> bulkCreate(@RequestBody BulkTaskRequest req) {
        return taskService.bulkCreate(req.tasks());
    }

    @GetMapping("/{id}")
    public Task get(@PathVariable Long id) {
        return taskService.get(id);
    }

    @PatchMapping("/{id}")
    public Task patch(@PathVariable Long id, @RequestBody TaskPatch patch) {
        return taskService.patch(id, patch);
    }

    @PostMapping("/{id}/migrate")
    public Task migrate(@PathVariable Long id, @RequestParam TaskGroup group) {
        return taskService.migrate(id, group);
    }

    @PostMapping("/submit")
    public TaskSummary submit() {
        return taskService.submit();
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        taskService.delete(id);
    }
}
