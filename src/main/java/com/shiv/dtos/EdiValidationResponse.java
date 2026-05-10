package com.shiv.dtos;


import lombok.Builder;

import java.util.List;

@Builder
public record EdiValidationResponse(boolean valid, int totalErrors, String summary, List<EdiValidationError> errors) {
}
