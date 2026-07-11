package com.yanban.api.project;

import java.util.List;

public record ProjectProvisioningRequest(Long userId, String name, String projectFolder,
                                         List<String> includeRules, List<String> ignoreRules) {
}
