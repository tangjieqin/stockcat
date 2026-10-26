package com.stockcat.web;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class HealthController {

    @GetMapping("/system/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "UP",
                "service", "stockcat-server",
                "version", "0.1.0-SNAPSHOT"
        );
    }
}
