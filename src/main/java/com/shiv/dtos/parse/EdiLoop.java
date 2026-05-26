package com.shiv.dtos.parse;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Builder;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Builder
public record EdiLoop(
        String loopId,
        int occurrence,            // which repetition of this loop at its level (1-based)
        String triggerSegment,     // tag of the first segment in this loop (e.g. "N1", "PO1")
        @JsonIgnore Map<String, List<EdiSegment>> segmentIndex, // Java-side lookup only; not in JSON
        List<EdiNode> children
) implements EdiNode {

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