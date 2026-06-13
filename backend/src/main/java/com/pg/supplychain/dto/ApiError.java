package com.pg.supplychain.dto;

import lombok.Builder;
import lombok.Getter;
import java.time.OffsetDateTime;

@Getter
@Builder
public class ApiError {
    private final int status;
    private final String error;
    private final String message;

    @Builder.Default
    private final String timestamp = OffsetDateTime.now().toString();
}
