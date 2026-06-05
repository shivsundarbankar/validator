package com.shiv.transform;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.shiv.dtos.EdiValidationResponse;

import java.util.Map;

/**
 * Response envelope returned by the transform endpoints.
 *
 * <ul>
 *   <li>{@code businessJson} is populated when {@code outputFormat = BUSINESS_JSON}</li>
 *   <li>{@code flatFile} is populated when {@code outputFormat = FLAT_FILE}</li>
 *   <li>When {@code transformed = false} the document could not be parsed; check {@code validation.errors}</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EdiTransformResult(
        boolean transformed,
        String transactionCode,
        String outputFormat,
        Map<String, Object> businessJson,
        String flatFile,
        EdiValidationResponse validation
) {}
