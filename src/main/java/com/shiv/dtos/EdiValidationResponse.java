package com.shiv.dtos;

import lombok.Builder;

import java.util.List;

@Builder
public record EdiValidationResponse(
        boolean valid,
        int totalErrors,
        String validationMode,   // SCHEMA_VALIDATION | STRUCTURAL_ONLY | PREFLIGHT_FAILED | ERROR
        String summary,
        List<String> warnings,
        List<EdiValidationError> errors
) {
}
