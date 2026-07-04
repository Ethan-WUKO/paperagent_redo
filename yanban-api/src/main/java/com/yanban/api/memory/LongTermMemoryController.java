package com.yanban.api.memory;

import com.yanban.api.security.JwtUser;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/settings/memory")
public class LongTermMemoryController {

    private final LongTermMemoryService memoryService;

    public LongTermMemoryController(LongTermMemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @GetMapping
    public List<LongTermMemoryResponse> list(@AuthenticationPrincipal JwtUser currentUser,
                                             @RequestParam(required = false) String status,
                                             @RequestParam(defaultValue = "50") Integer limit) {
        return memoryService.listMemories(currentUser.id(), status, limit);
    }

    @GetMapping("/{memoryId}")
    public LongTermMemoryResponse get(@AuthenticationPrincipal JwtUser currentUser,
                                      @PathVariable Long memoryId) {
        return memoryService.getMemory(currentUser.id(), memoryId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public LongTermMemoryResponse create(@AuthenticationPrincipal JwtUser currentUser,
                                         @Valid @RequestBody CreateLongTermMemoryRequest request) {
        return memoryService.createMemory(currentUser.id(), request);
    }

    @PutMapping("/{memoryId}")
    public LongTermMemoryResponse correct(@AuthenticationPrincipal JwtUser currentUser,
                                          @PathVariable Long memoryId,
                                          @Valid @RequestBody UpdateLongTermMemoryRequest request) {
        return memoryService.correctMemory(currentUser.id(), memoryId, request);
    }

    @DeleteMapping("/{memoryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal JwtUser currentUser,
                       @PathVariable Long memoryId) {
        memoryService.deleteMemory(currentUser.id(), memoryId);
    }
}
