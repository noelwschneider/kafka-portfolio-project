package com.orderfulfillment.scenario.dto;

import java.util.List;

public record EventRecordPageDto(
        List<EventRecordDto> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
}
