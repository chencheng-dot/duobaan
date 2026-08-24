package org.example.duobaan.repository;

import java.util.List;

import org.example.duobaan.model.Task;
import org.example.duobaan.model.TaskGroup;
import org.example.duobaan.model.TaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TaskRepository extends JpaRepository<Task, Long> {

    List<Task> findByGroupOrderByCreatedAtAsc(TaskGroup group);

    long countByGroupAndStatus(TaskGroup group, TaskStatus status);
}
