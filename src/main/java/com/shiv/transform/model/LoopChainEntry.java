package com.shiv.transform.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One step in a nested loop navigation path used by
 * {@link RuleType#LOOP_FIELD_NESTED} and {@link RuleType#LOOP_ARRAY_NESTED}.
 *
 * <p>Example — navigate to the HL shipment loop:
 * <pre>{ "trigger": "HL", "filterPosition": 3, "filterValue": "S" }</pre>
 *
 * <p>Example — navigate into an N1 ship-from loop:
 * <pre>{ "trigger": "N1", "filterPosition": 1, "filterValue": "SF" }</pre>
 *
 * <p>If {@code filterValue} is null the first loop with the matching trigger is taken.
 * If {@code filterSegment} is null it defaults to {@code trigger}.
 * If {@code filterPosition} is null it defaults to 1.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LoopChainEntry(
        String trigger,         // trigger segment tag of the loop to navigate into (e.g. "HL", "N1")
        String filterSegment,   // segment inside the loop to filter on; null → same as trigger
        Integer filterPosition, // element position for the filter; null → 1
        String filterValue      // expected value at filterPosition; null → take first match
) {}
