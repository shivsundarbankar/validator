package com.shiv.dtos.parse;

import com.shiv.dtos.EdiValidationResponse;

public record EdiParseResult(
        boolean parsed,
        EdiDocument document,           // non-null when parsed = true
        EdiValidationResponse validation // always present; errors list is empty when parsed = true and document is valid
) {}