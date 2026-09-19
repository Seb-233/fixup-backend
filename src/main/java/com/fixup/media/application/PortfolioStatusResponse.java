package com.fixup.media.application;

import java.time.Instant;
import java.util.UUID;

public record PortfolioStatusResponse(UUID fixerUserId, String status, Instant publishedAt) {
}
