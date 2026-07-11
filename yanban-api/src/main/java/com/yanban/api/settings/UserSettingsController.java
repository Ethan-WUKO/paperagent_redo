package com.yanban.api.settings;

import com.yanban.api.security.JwtUser;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/settings")
public class UserSettingsController {

    private final UserSettingsService userSettingsService;

    public UserSettingsController(UserSettingsService userSettingsService) {
        this.userSettingsService = userSettingsService;
    }

    @GetMapping
    public UserSettingsResponse get(@AuthenticationPrincipal JwtUser currentUser) {
        return userSettingsService.get(currentUser.id());
    }

    @PutMapping
    public UserSettingsResponse update(@AuthenticationPrincipal JwtUser currentUser,
                                       @Valid @RequestBody UserSettingsRequest request) {
        return userSettingsService.update(currentUser.id(), request);
    }

    @PostMapping("/providers/{provider}/models/refresh")
    public UserSettingsResponse refreshProviderModels(@AuthenticationPrincipal JwtUser currentUser,
                                                      @PathVariable String provider) {
        return userSettingsService.refreshProviderModels(currentUser.id(), provider);
    }
}
