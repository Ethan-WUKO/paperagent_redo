package com.yanban.core.user;

public interface UserAccountPolicy {

    boolean isDemoUser(Long userId);

    void assertSettingsMutable(Long userId);

    void assertCanUploadKnowledge(Long userId, long bytes);

    void assertCanDeleteKnowledgeDocument(Long userId, String sourceType);

    void assertCanCreatePaperTask(Long userId, long bytes);

    void assertCanSendChatMessage(Long userId);
}
