package com.shiv.dtos.parse;

import lombok.Builder;

import java.util.List;

@Builder
public record EdiElement(
        int position,
        String value,       // raw value for simple elements; colon-joined components for composites
        List<String> components  // empty for simple elements, component list for composites
) {}