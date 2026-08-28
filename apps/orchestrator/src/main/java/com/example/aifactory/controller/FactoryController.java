package com.example.aifactory.controller;

import com.example.aifactory.config.AiFactoryProperties;
import com.example.aifactory.model.CloudAvailability;
import com.example.aifactory.model.FactoryCapabilities;
import com.example.aifactory.service.LlmGatewayClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api")
public class FactoryController {
    private final AiFactoryProperties props;
    private final LlmGatewayClient llm;

    public FactoryController(AiFactoryProperties props, LlmGatewayClient llm) {
        this.props = props;
        this.llm = llm;
    }

    @GetMapping("/capabilities")
    public Mono<FactoryCapabilities> capabilities() {
        return Mono.fromCallable(() -> {
                    CloudAvailability cloud = llm.cloudAvailability();
                    return new FactoryCapabilities(props.cloudEnabled(), cloud.available(), cloud.error());
                })
                .subscribeOn(Schedulers.boundedElastic());
    }
}
