package com.yanban.api.memory;

import java.time.Instant;

public record UpdateLongTermMemoryExpiryRequest(Instant expiresAt) {
}
