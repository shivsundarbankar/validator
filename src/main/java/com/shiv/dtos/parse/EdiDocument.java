package com.shiv.dtos.parse;

import lombok.Builder;

import java.time.Instant;

@Builder
public record EdiDocument(
        String documentType,    // e.g. "850", "810"
        String version,         // normalized (e.g. "004010")
        String senderId,
        String receiverId,
        String validationMode,  // SCHEMA_VALIDATION | STRUCTURAL_ONLY
        boolean loopsResolved,  // true when a schema was applied and loop events fired
        Instant parsedAt,
        EdiInterchange interchange
) {}