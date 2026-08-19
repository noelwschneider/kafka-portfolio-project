package com.orderfulfillment.scenario.dto;

import java.time.Instant;
import java.util.List;

public record ResetResultDto(
        boolean inventoryRestored,
        List<String> consumersResumed,
        boolean paymentBehaviorCleared,
        Instant resetAt
) {
}
