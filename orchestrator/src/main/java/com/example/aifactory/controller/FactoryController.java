package com.example.aifactory.controller;

import com.example.aifactory.config.AiFactoryProperties;
import com.example.aifactory.model.FactoryCapabilities;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class FactoryController {
    private final AiFactoryProperties props;

    public FactoryController(AiFactoryProperties props) {
        this.props = props;
    }

    @GetMapping("/capabilities")
    public FactoryCapabilities capabilities() {
        return new FactoryCapabilities(props.cloudEnabled());
    }
}
