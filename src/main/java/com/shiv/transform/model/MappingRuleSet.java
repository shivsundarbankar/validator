package com.shiv.transform.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * The full transformation request body sent by the frontend.
 *
 * <p>Example (business JSON):
 * <pre>{@code
 * {
 *   "outputFormat": "BUSINESS_JSON",
 *   "rules": [
 *     { "type": "SEGMENT_FIELD", "segment": "BEG", "position": 3, "field": "orderNumber" },
 *     { "type": "LOOP_FIELD",    "loopTrigger": "N1", "filterSegment": "N1",
 *       "filterPosition": 1, "filterValue": "ST", "segment": "N1", "position": 3, "field": "shipTo.name" },
 *     { "type": "LOOP_ARRAY",    "loopTrigger": "PO1", "arrayField": "lineItems",
 *       "itemRules": [
 *         { "segment": "PO1", "position": 2, "field": "quantity" },
 *         { "segment": "PO1", "position": 4, "field": "unitPrice" }
 *       ]
 *     }
 *   ]
 * }
 * }</pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MappingRuleSet(
        OutputFormat outputFormat,     // defaults to BUSINESS_JSON when null
        List<MappingRule> rules,
        FlatFileConfig flatFileConfig  // required only when outputFormat = FLAT_FILE
) {
    public OutputFormat effectiveOutputFormat() {
        return outputFormat != null ? outputFormat : OutputFormat.BUSINESS_JSON;
    }
}
