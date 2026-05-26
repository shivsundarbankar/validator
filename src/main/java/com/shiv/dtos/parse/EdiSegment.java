package com.shiv.dtos.parse;

import lombok.Builder;

import java.util.List;

@Builder
public record EdiSegment(
        String tag,
        int position,
        List<EdiElement> elements
) implements EdiNode {

    /**
     * Returns the value of the element at the given 1-based position, or "" if absent.
     * Example: segment.get(3) → BEG03 value.
     */
    public String get(int position) {
        return elements.stream()
                .filter(e -> e.position() == position)
                .map(EdiElement::value)
                .findFirst()
                .orElse("");
    }
}
