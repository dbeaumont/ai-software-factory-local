package com.example.demo;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/customers")
public class CustomerController {
    @GetMapping
    public List<Map<String, Object>> all() {
        return List.of(
                Map.of("id", 1, "name", "Ada"),
                Map.of("id", 2, "name", "Linus")
        );
    }
}
