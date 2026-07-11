package com.yanban.paper.service;

public final class PaperModelExecutionContext {

    private static final ThreadLocal<Long> CURRENT_USER_ID = new ThreadLocal<>();

    private PaperModelExecutionContext() {
    }

    public static Scope open(Long userId) {
        Long previous = CURRENT_USER_ID.get();
        if (userId == null) {
            CURRENT_USER_ID.remove();
        } else {
            CURRENT_USER_ID.set(userId);
        }
        return new Scope(previous);
    }

    public static Long currentUserId() {
        return CURRENT_USER_ID.get();
    }

    public static final class Scope implements AutoCloseable {
        private final Long previous;

        private Scope(Long previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (previous == null) {
                CURRENT_USER_ID.remove();
            } else {
                CURRENT_USER_ID.set(previous);
            }
        }
    }
}
