package com.yanban.api.observability;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AgentObservabilityController {

    private final AgentObservabilityService service;

    public AgentObservabilityController(AgentObservabilityService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/observability/dashboard")
    public AgentObservabilityService.DashboardResponse dashboard(
            @RequestParam(name = "windowMinutes", required = false) Integer windowMinutes) {
        return service.dashboard(windowMinutes);
    }

    @GetMapping("/api/v1/observability/alerts")
    public AgentObservabilityService.AlertResponse alerts(
            @RequestParam(name = "windowMinutes", required = false) Integer windowMinutes) {
        return service.alerts(windowMinutes);
    }
}
