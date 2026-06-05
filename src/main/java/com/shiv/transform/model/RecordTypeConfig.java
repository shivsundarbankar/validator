package com.shiv.transform.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Defines one record type in a flat-file output.
 *
 * <p>If {@code sourceArray} is null the record is emitted once (header/trailer).
 * If {@code sourceArray} is set the record is emitted once per item in that array.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RecordTypeConfig(
        String prefix,        // record prefix written as the first field (e.g. "ORDER", "LINE")
        String sourceArray,   // business-JSON array field to iterate over (null = single record)
        List<String> fields   // ordered field names to write (dot notation supported for nested fields)
) {}
