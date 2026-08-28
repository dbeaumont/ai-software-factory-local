package com.example.aifactory.controller;

import com.example.aifactory.model.TaskRequest;
import com.example.aifactory.model.TaskView;
import com.example.aifactory.service.TaskService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {
    private final TaskService tasks;

    public TaskController(TaskService tasks) { this.tasks = tasks; }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<TaskView> create(@RequestBody TaskRequest request) {
        return Mono.fromCallable(() -> tasks.create(request))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping
    public List<TaskView> list() { return tasks.list(); }

    @GetMapping("/{id}")
    public TaskView get(@PathVariable String id) { return tasks.get(id); }

    @PostMapping("/{id}/approve")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public TaskView approve(@PathVariable String id) { return tasks.approve(id); }
}
