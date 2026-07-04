package com.yanban.api.demo;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/demo")
public class DemoController {

    private final DemoProperties properties;

    public DemoController(DemoProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/config")
    public DemoConfigResponse config() {
        return DemoConfigResponse.from(properties);
    }
}
