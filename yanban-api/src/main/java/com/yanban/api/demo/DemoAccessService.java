package com.yanban.api.demo;

import com.yanban.api.user.SysUser;
import com.yanban.api.user.SysUserRepository;
import com.yanban.core.agent.AgentMessageRepository;
import com.yanban.core.user.UserAccountPolicy;
import com.yanban.knowledge.domain.KbDocumentRepository;
import com.yanban.paper.domain.PaperTaskRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DemoAccessService implements UserAccountPolicy {

    public static final String ACCOUNT_TYPE_DEMO = "DEMO";
    public static final String SOURCE_TYPE_DEMO_SEED = "DEMO_SEED";
    public static final String SOURCE_TYPE_USER_UPLOAD = "USER_UPLOAD";

    private final DemoProperties properties;
    private final SysUserRepository users;
    private final AgentMessageRepository messages;
    private final KbDocumentRepository documents;
    private final PaperTaskRepository paperTasks;

    public DemoAccessService(DemoProperties properties,
                             SysUserRepository users,
                             AgentMessageRepository messages,
                             KbDocumentRepository documents,
                             PaperTaskRepository paperTasks) {
        this.properties = properties;
        this.users = users;
        this.messages = messages;
        this.documents = documents;
        this.paperTasks = paperTasks;
    }

    @Override
    public boolean isDemoUser(Long userId) {
        if (userId == null) {
            return false;
        }
        return users.findById(userId)
                .map(SysUser::getAccountType)
                .map(ACCOUNT_TYPE_DEMO::equalsIgnoreCase)
                .orElse(false);
    }

    @Override
    public void assertSettingsMutable(Long userId) {
        if (isDemoUser(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Demo 账号不能修改模型、API Key、MCP 或技能配置。");
        }
    }

    @Override
    public void assertCanUploadKnowledge(Long userId, long bytes) {
        if (!isDemoUser(userId)) {
            return;
        }
        assertUploadSize(bytes);
        long uploads = documents.countByUserIdAndSourceType(userId, SOURCE_TYPE_USER_UPLOAD);
        if (uploads >= properties.getMaxUploadsPerReset()) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Demo 账号上传次数已达上限，数据会在下次重置后恢复。");
        }
    }

    @Override
    public void assertCanDeleteKnowledgeDocument(Long userId, String sourceType) {
        if (isDemoUser(userId) && SOURCE_TYPE_DEMO_SEED.equalsIgnoreCase(sourceType)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Demo 预置文档不能删除。");
        }
    }

    @Override
    public void assertCanCreatePaperTask(Long userId, long bytes) {
        if (!isDemoUser(userId)) {
            return;
        }
        assertUploadSize(bytes);
        if (paperTasks.countByUserId(userId) >= properties.getMaxPaperTasksPerReset()) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Demo 账号论文任务数量已达上限，数据会在下次重置后恢复。");
        }
    }

    @Override
    public void assertCanSendChatMessage(Long userId) {
        if (!isDemoUser(userId)) {
            return;
        }
        Instant since = Instant.now().minus(1, ChronoUnit.HOURS);
        long sent = messages.countByUserIdAndRoleAndCreatedAtAfter(userId, "user", since);
        if (sent >= properties.getMaxChatMessagesPerHour()) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Demo 账号消息频率已达上限，请稍后再试。");
        }
    }

    private void assertUploadSize(long bytes) {
        if (bytes > properties.getMaxUploadBytes()) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Demo 上传文件不能超过 "
                    + Math.max(1, properties.getMaxUploadBytes() / 1024 / 1024) + "MB。");
        }
    }
}
