package com.shiv.transform.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * One transformation rule sent from the frontend.
 *
 * <p>Field usage by rule type:
 * <ul>
 *   <li>SEGMENT_FIELD           — segment, position, field</li>
 *   <li>SEGMENT_FIELD_QUALIFIED — segment, filterPosition, filterValue, position, field</li>
 *   <li>LOOP_FIELD              — loopTrigger, segment, position, field [+ filterSegment/filterPosition/filterValue]</li>
 *   <li>LOOP_ARRAY              — loopTrigger, arrayField, itemRules</li>
 *   <li>LOOP_FIELD_NESTED       — loopChain, segment, position, field [+ filterPosition/filterValue for segment qualifier]</li>
 *   <li>LOOP_ARRAY_NESTED       — loopChain, loopTrigger, arrayField, itemRules [+ filterPosition/filterValue for array loop filter]</li>
 * </ul>
 *
 * <p>Dot notation is supported for {@code field} and {@code arrayField}, e.g. {@code "shipTo.name"}
 * will create a nested object: {@code { "shipTo": { "name": "..." } }}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MappingRule(

        // ── common ───────────────────────────────────────────────────────────
        RuleType type,

        // ── SEGMENT_FIELD / LOOP_FIELD / LOOP_FIELD_NESTED ───────────────────
        String segment,          // segment tag to read (e.g. "BEG", "REF")
        Integer position,        // 1-based element position within that segment
        String field,            // output field name (dot notation supported)

        // ── LOOP_FIELD / LOOP_ARRAY / LOOP_ARRAY_NESTED ───────────────────────
        String loopTrigger,      // trigger segment of the loop to search or iterate

        // ── qualifier filter (shared across several types) ────────────────────
        // LOOP_FIELD         : filterSegment/filterPosition/filterValue narrow which loop occurrence
        // LOOP_FIELD_NESTED  : filterPosition/filterValue do qualified segment lookup inside target loop
        // LOOP_ARRAY_NESTED  : filterPosition/filterValue filter the array-level loops
        String filterSegment,    // segment to use for loop-level filter (LOOP_FIELD)
        Integer filterPosition,  // element position for filter / qualifier
        String filterValue,      // expected value for filter / qualifier

        // ── LOOP_ARRAY / LOOP_ARRAY_NESTED ────────────────────────────────────
        String arrayField,       // output array field name (dot notation supported)
        List<ItemRule> itemRules, // per-item field mappings

        // ── LOOP_FIELD_NESTED / LOOP_ARRAY_NESTED ─────────────────────────────
        List<LoopChainEntry> loopChain  // ordered navigation path through nested loops
) {}
