package com.shiv.transform.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A single field mapping inside a LOOP_ARRAY rule.
 * Describes how to extract one element from each loop occurrence into an output field.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ItemRule(
        String segment,  // segment tag within the loop (e.g. "PO1", "CTP")
        int position,    // 1-based element position within that segment
        String field     // output field name in the resulting array item object
) {}
