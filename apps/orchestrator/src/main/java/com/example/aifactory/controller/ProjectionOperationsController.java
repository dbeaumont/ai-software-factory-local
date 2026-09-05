package com.example.aifactory.controller;

import com.example.aifactory.workflow.projection.ProjectionRebuildCommand;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/operations/projections")
public final class ProjectionOperationsController {
    private final ProjectionRebuildCommand command;

    public ProjectionOperationsController(ProjectionRebuildCommand command) {
        this.command = command;
    }

    @PostMapping("/rebuild")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ProjectionRebuildCommand.Result rebuild(@RequestBody ProjectionRebuildCommand.Request request) {
        return command.execute(request);
    }
}
