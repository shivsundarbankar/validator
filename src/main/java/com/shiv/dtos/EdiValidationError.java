package com.shiv.dtos;


import lombok.Builder;

@Builder
public record EdiValidationError(String errorType,       // e.g. MANDATORY_ELEMENT_MISSING
                                 String eventType,       // e.g. ELEMENT_DATA_ERROR
                                 String segmentTag,      // e.g. BEG, PO1
                                 int segmentPosition, // position in document
                                 int elementPosition, // position within segment
                                 int componentPosition, String value            // the actual bad value (if any)
) {
}