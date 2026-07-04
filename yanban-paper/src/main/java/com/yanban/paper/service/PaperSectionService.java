package com.yanban.paper.service;

import com.yanban.paper.domain.PaperSection;
import com.yanban.paper.domain.PaperSectionRepository;
import com.yanban.paper.domain.PaperTaskRepository;
import com.yanban.paper.latex.LatexSectionRole;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PaperSectionService {

    private final PaperTaskRepository tasks;
    private final PaperSectionRepository sections;

    public PaperSectionService(PaperTaskRepository tasks, PaperSectionRepository sections) {
        this.tasks = tasks;
        this.sections = sections;
    }

    @Transactional(readOnly = true)
    public List<PaperSection> list(Long userId, Long taskId) {
        ensureOwnedTask(userId, taskId);
        return sections.findByTaskIdOrderByOrderIndexAsc(taskId);
    }

    @Transactional
    public PaperSection updateRole(Long userId, Long taskId, Long sectionId, String role) {
        ensureOwnedTask(userId, taskId);
        String normalizedRole = normalizeRole(role);
        PaperSection section = sections.findByIdAndTaskId(sectionId, taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "章节不存在"));
        section.setRole(normalizedRole);
        section.setRoleSource("user");
        section.setRoleConfidence(1.0);
        return sections.save(section);
    }

    private void ensureOwnedTask(Long userId, Long taskId) {
        tasks.findByIdAndUserId(taskId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "论文任务不存在"));
    }

    private String normalizeRole(String role) {
        if (role == null || role.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "role 不能为空");
        }
        try {
            return LatexSectionRole.valueOf(role.trim().toUpperCase(Locale.ROOT)).name();
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的章节角色: " + role);
        }
    }
}
