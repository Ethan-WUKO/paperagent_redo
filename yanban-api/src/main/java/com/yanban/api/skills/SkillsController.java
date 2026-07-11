package com.yanban.api.skills;

import com.yanban.api.security.JwtUser;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/skills")
public class SkillsController {

    private final SkillsService skillsService;

    public SkillsController(SkillsService skillsService) {
        this.skillsService = skillsService;
    }

    @GetMapping
    public List<SkillListItemResponse> listSkills(@AuthenticationPrincipal JwtUser currentUser) {
        return skillsService.listSkills(currentUser.id());
    }

    @PutMapping("/{skillId}/enabled")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setEnabled(@AuthenticationPrincipal JwtUser currentUser,
                           @PathVariable String skillId,
                           @RequestBody SkillEnabledRequest request) {
        skillsService.setEnabled(currentUser.id(), skillId, request.enabled());
    }

    @PostMapping("/refresh")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void refresh() {
        skillsService.refresh();
    }
}
