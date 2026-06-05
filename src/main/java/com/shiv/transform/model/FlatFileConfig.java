package com.shiv.transform.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Configuration for flat-file serialization.
 * Required only when {@link MappingRuleSet#outputFormat()} is {@link OutputFormat#FLAT_FILE}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FlatFileConfig(
        String delimiter,                  // field delimiter; defaults to "|" when null
        List<RecordTypeConfig> recordTypes // ordered list of record type definitions
) {
    public String effectiveDelimiter() {
        return (delimiter != null && !delimiter.isEmpty()) ? delimiter : "|";
    }
}
