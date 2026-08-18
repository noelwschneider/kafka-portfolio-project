package com.orderfulfillment.common;

import java.time.Instant;
import java.util.UUID;

/**
 * Shared error envelope from docs/planning/high-level-design.md's API Error Model section,
 * mirrored by every service's ApiError schema in docs/openapi/*.yaml.
 */
public record ApiError(
        Instant timestamp,
        int status,
        String error,
        String code,
        String message,
        String path,
        UUID correlationId
) {
}
