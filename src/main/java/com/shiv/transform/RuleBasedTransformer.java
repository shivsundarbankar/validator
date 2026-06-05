package com.shiv.transform;

import com.shiv.dtos.parse.EdiLoop;
import com.shiv.dtos.parse.EdiSegment;
import com.shiv.dtos.parse.EdiTransaction;
import com.shiv.transform.model.ItemRule;
import com.shiv.transform.model.LoopChainEntry;
import com.shiv.transform.model.MappingRule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Generic, rule-driven transformer: converts an {@link EdiTransaction} into a
 * {@code Map<String, Object>} whose shape is entirely defined by the caller-supplied rules.
 *
 * <p>No per-transaction-type mapper classes are needed. Rules arrive from the frontend at
 * request time; adding support for a new EDI transaction requires zero code changes here.
 *
 * <p>Rule failures are logged and skipped so a bad rule never aborts the whole transform.
 */
@Slf4j
@Service
public class RuleBasedTransformer {

    public Map<String, Object> transform(EdiTransaction transaction, List<MappingRule> rules) {
        Map<String, Object> output = new LinkedHashMap<>();
        for (MappingRule rule : rules) {
            try {
                switch (rule.type()) {
                    case SEGMENT_FIELD           -> applySegmentField(transaction, rule, output);
                    case SEGMENT_FIELD_QUALIFIED -> applySegmentFieldQualified(transaction, rule, output);
                    case LOOP_FIELD              -> applyLoopField(transaction, rule, output);
                    case LOOP_ARRAY              -> applyLoopArray(transaction, rule, output);
                    case LOOP_FIELD_NESTED       -> applyLoopFieldNested(transaction, rule, output);
                    case LOOP_ARRAY_NESTED       -> applyLoopArrayNested(transaction, rule, output);
                }
            } catch (Exception e) {
                log.warn("Rule skipped [type={} target={}]: {}",
                        rule.type(), targetField(rule), e.getMessage());
            }
        }
        return output;
    }

    // ── Flat rule handlers ────────────────────────────────────────────────────

    /** SEGMENT_FIELD: read an element from a top-level segment → scalar field (first occurrence). */
    private void applySegmentField(EdiTransaction tx, MappingRule rule, Map<String, Object> out) {
        tx.segment(rule.segment())
          .ifPresent(seg -> putNested(out, rule.field(), seg.get(rule.position())));
    }

    /**
     * SEGMENT_FIELD_QUALIFIED: like SEGMENT_FIELD but scans ALL occurrences of the segment
     * and picks the one whose element at {@code filterPosition} equals {@code filterValue}.
     */
    private void applySegmentFieldQualified(EdiTransaction tx, MappingRule rule, Map<String, Object> out) {
        List<EdiSegment> all = tx.segmentIndex().get(rule.segment());
        if (all == null || all.isEmpty()) {
            log.debug("SEGMENT_FIELD_QUALIFIED: segment '{}' not found", rule.segment());
            return;
        }
        int qualPos = rule.filterPosition() != null ? rule.filterPosition() : 1;
        all.stream()
           .filter(seg -> rule.filterValue() != null && rule.filterValue().equals(seg.get(qualPos)))
           .findFirst()
           .ifPresentOrElse(
               seg -> putNested(out, rule.field(), seg.get(rule.position())),
               () -> log.debug("SEGMENT_FIELD_QUALIFIED: no '{}' with pos{}='{}'",
                       rule.segment(), qualPos, rule.filterValue())
           );
    }

    /** LOOP_FIELD: find one loop occurrence (optionally filtered) → scalar field. */
    private void applyLoopField(EdiTransaction tx, MappingRule rule, Map<String, Object> out) {
        tx.loops(rule.loopTrigger()).stream()
          .filter(loop -> matchesLoopFilter(loop, rule))
          .findFirst()
          .ifPresentOrElse(
              loop -> loop.segment(rule.segment())
                          .ifPresent(seg -> putNested(out, rule.field(), seg.get(rule.position()))),
              () -> log.debug("LOOP_FIELD: no match [loopTrigger={} filter={}={}]",
                      rule.loopTrigger(), rule.filterSegment(), rule.filterValue())
          );
    }

    /**
     * LOOP_ARRAY: map every occurrence of a repeating loop → array of objects.
     * Optional {@code filterSegment/filterPosition/filterValue} narrows which loops
     * are included (e.g. only HL loops where HL[3]="I" for 856 item level).
     */
    private void applyLoopArray(EdiTransaction tx, MappingRule rule, Map<String, Object> out) {
        List<Map<String, Object>> array = tx.loops(rule.loopTrigger()).stream()
                .filter(loop -> matchesLoopFilter(loop, rule))
                .map(loop -> buildArrayItem(loop, rule.itemRules()))
                .filter(item -> !item.isEmpty())
                .toList();
        out.put(rule.arrayField(), array);
    }

    // ── Nested rule handlers ──────────────────────────────────────────────────

    /**
     * LOOP_FIELD_NESTED: follow a {@code loopChain} to reach a deeply nested loop,
     * then extract one element from a segment inside it.
     *
     * <p>Optional qualified-segment filter: if {@code filterValue} is set, all occurrences
     * of {@code segment} in the target loop are scanned and the one whose element at
     * {@code filterPosition} matches {@code filterValue} is used (handles multiple REF segments).
     */
    private void applyLoopFieldNested(EdiTransaction tx, MappingRule rule, Map<String, Object> out) {
        navigateChain(tx, rule.loopChain()).ifPresentOrElse(
            target -> {
                if (rule.filterValue() != null) {
                    // Qualified segment lookup within the target loop
                    List<EdiSegment> all = target.segmentIndex().get(rule.segment());
                    if (all == null || all.isEmpty()) {
                        log.debug("LOOP_FIELD_NESTED: segment '{}' not in target loop", rule.segment());
                        return;
                    }
                    int qualPos = rule.filterPosition() != null ? rule.filterPosition() : 1;
                    all.stream()
                       .filter(seg -> rule.filterValue().equals(seg.get(qualPos)))
                       .findFirst()
                       .ifPresentOrElse(
                           seg -> putNested(out, rule.field(), seg.get(rule.position())),
                           () -> log.debug("LOOP_FIELD_NESTED: no '{}' with pos{}='{}' in target loop",
                                   rule.segment(), qualPos, rule.filterValue())
                       );
                } else {
                    target.segment(rule.segment())
                          .ifPresent(seg -> putNested(out, rule.field(), seg.get(rule.position())));
                }
            },
            () -> log.debug("LOOP_FIELD_NESTED: chain did not resolve [field='{}']", rule.field())
        );
    }

    /**
     * LOOP_ARRAY_NESTED: follow a {@code loopChain} to reach the parent loop,
     * then map every child loop matching {@code loopTrigger} (and optional filter)
     * into an array of objects.
     */
    private void applyLoopArrayNested(EdiTransaction tx, MappingRule rule, Map<String, Object> out) {
        navigateChain(tx, rule.loopChain()).ifPresentOrElse(
            parent -> {
                // Resolve filter segment: defaults to loopTrigger if not set
                String effectiveFilterSeg = rule.filterSegment() != null
                        ? rule.filterSegment() : rule.loopTrigger();
                int filterPos = rule.filterPosition() != null ? rule.filterPosition() : 1;

                List<Map<String, Object>> array = parent.loops(rule.loopTrigger()).stream()
                    .filter(loop -> rule.filterValue() == null ||
                            loop.segment(effectiveFilterSeg)
                                .map(seg -> seg.get(filterPos))
                                .map(rule.filterValue()::equals)
                                .orElse(false))
                    .map(loop -> buildArrayItem(loop, rule.itemRules()))
                    .filter(item -> !item.isEmpty())
                    .toList();

                out.put(rule.arrayField(), array);
            },
            () -> log.debug("LOOP_ARRAY_NESTED: chain did not resolve [arrayField='{}']", rule.arrayField())
        );
    }

    // ── Navigation ────────────────────────────────────────────────────────────

    /**
     * Walks a {@code loopChain} starting from the transaction's direct children.
     * At each step finds the first loop matching the entry's trigger and filter,
     * then descends into it for the next step.
     *
     * @return the loop reached at the end of the chain, or empty if any step fails
     */
    private Optional<EdiLoop> navigateChain(EdiTransaction tx, List<LoopChainEntry> chain) {
        if (chain == null || chain.isEmpty()) return Optional.empty();

        Optional<EdiLoop> current = tx.loops(chain.get(0).trigger()).stream()
                .filter(loop -> matchesChainEntry(loop, chain.get(0)))
                .findFirst();

        for (int i = 1; i < chain.size(); i++) {
            if (current.isEmpty()) return Optional.empty();
            LoopChainEntry entry = chain.get(i);
            current = current.get().loops(entry.trigger()).stream()
                    .filter(loop -> matchesChainEntry(loop, entry))
                    .findFirst();
        }
        return current;
    }

    private boolean matchesChainEntry(EdiLoop loop, LoopChainEntry entry) {
        if (entry.filterValue() == null) return true;
        String filterSeg = entry.filterSegment() != null ? entry.filterSegment() : entry.trigger();
        int filterPos    = entry.filterPosition() != null ? entry.filterPosition() : 1;
        return loop.segment(filterSeg)
                .map(seg -> seg.get(filterPos))
                .map(entry.filterValue()::equals)
                .orElse(false);
    }

    // ── Shared helpers ────────────────────────────────────────────────────────

    private Map<String, Object> buildArrayItem(EdiLoop loop, List<ItemRule> itemRules) {
        Map<String, Object> item = new LinkedHashMap<>();
        for (ItemRule ir : itemRules) {
            loop.segment(ir.segment())
                .map(seg -> seg.get(ir.position()))
                .filter(v -> v != null && !v.isBlank())
                .ifPresent(v -> item.put(ir.field(), v));
        }
        return item;
    }

    /** Filter used by LOOP_FIELD: matches a loop by checking a segment element inside it. */
    private boolean matchesLoopFilter(EdiLoop loop, MappingRule rule) {
        if (rule.filterSegment() == null) return true;
        int filterPos = rule.filterPosition() != null ? rule.filterPosition() : 1;
        String actual = loop.segment(rule.filterSegment())
                .map(seg -> seg.get(filterPos))
                .orElse("");
        return rule.filterValue() != null && rule.filterValue().equals(actual);
    }

    /**
     * Writes {@code value} into {@code root} at the path described by {@code path}.
     * Dot notation creates nested maps: {@code "shipTo.name"} →
     * {@code { "shipTo": { "name": value } }}.
     * Blank values are silently ignored.
     */
    @SuppressWarnings("unchecked")
    private void putNested(Map<String, Object> root, String path, String value) {
        if (value == null || value.isBlank()) return;
        String[] parts = path.split("\\.", -1);
        Map<String, Object> current = root;
        for (int i = 0; i < parts.length - 1; i++) {
            Object existing = current.get(parts[i]);
            if (existing instanceof Map<?, ?>) {
                current = (Map<String, Object>) existing;
            } else {
                Map<String, Object> nested = new LinkedHashMap<>();
                current.put(parts[i], nested);
                current = nested;
            }
        }
        current.put(parts[parts.length - 1], value);
    }

    private static String targetField(MappingRule rule) {
        if (rule.field() != null) return rule.field();
        if (rule.arrayField() != null) return rule.arrayField();
        return "unknown";
    }
}
