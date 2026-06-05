package com.shiv.transform.model;

public enum RuleType {
    /** Extract a top-level segment element into a scalar field (first occurrence of the segment). */
    SEGMENT_FIELD,

    /**
     * Like SEGMENT_FIELD but targets a specific occurrence of a segment identified by a qualifier.
     * Uses filterPosition + filterValue to select the right segment (e.g. DTM where DTM01="002").
     * Required fields: segment, filterPosition, filterValue, position, field.
     */
    SEGMENT_FIELD_QUALIFIED,

    /** Find a loop (optionally filtered by a field value) and extract one element into a scalar field. */
    LOOP_FIELD,

    /** Map every occurrence of a repeating loop into an array of objects. */
    LOOP_ARRAY,

    /**
     * Navigate a chain of nested loops (via {@code loopChain}), then extract one element
     * from a segment inside the final loop.
     * Supports an optional qualifier filter on the segment (like SEGMENT_FIELD_QUALIFIED).
     * Required fields: loopChain, segment, position, field.
     */
    LOOP_FIELD_NESTED,

    /**
     * Navigate a chain of nested loops (via {@code loopChain}) to reach the parent loop,
     * then map every matching child loop into an array of objects.
     * Required fields: loopChain, loopTrigger, arrayField, itemRules.
     */
    LOOP_ARRAY_NESTED
}
