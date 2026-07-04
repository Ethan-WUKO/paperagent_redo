package com.yanban.api.demo;

import java.util.List;

public record DemoConfigResponse(
        boolean enabled,
        String canonicalUrl,
        List<String> exampleQuestions,
        String notice,
        List<String> limitations
) {
    static DemoConfigResponse from(DemoProperties properties) {
        return new DemoConfigResponse(
                properties.isEnabled(),
                properties.getCanonicalUrl(),
                properties.getExampleQuestions(),
                "演示环境会定期清理，请不要上传隐私资料或真实论文原稿。",
                List.of(
                        "Demo 账号共享使用，聊天和上传记录每天自动重置。",
                        "Demo 用户不能修改模型、API Key、MCP 或技能配置。",
                        "单个上传文件最大 " + Math.max(1, properties.getMaxUploadBytes() / 1024 / 1024) + "MB。"
                )
        );
    }
}
