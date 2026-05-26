package com.shiv.dtos.parse;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Builder;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Builder
public record EdiTransaction(
        String transactionCode,    // ST01 (e.g. "850", "810")
        String controlNumber,      // ST02
        @JsonIgnore Map<String, List<EdiSegment>> segmentIndex, // Java-side lookup only; not in JSON
        List<EdiNode> children     // ordered mix of EdiSegment and EdiLoop
) {

    public Optional<EdiSegment> segment(String tag) {
        List<EdiSegment> segs = segmentIndex.get(tag);
        return (segs != null && !segs.isEmpty()) ? Optional.of(segs.get(0)) : Optional.empty();
    }

    public List<EdiLoop> loops(String triggerTag) {
        return children.stream()
                .filter(n -> n instanceof EdiLoop l && triggerTag.equals(l.triggerSegment()))
                .map(n -> (EdiLoop) n)
                .toList();
    }
}