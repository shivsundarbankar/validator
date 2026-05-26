package com.shiv.dtos.parse;

import lombok.Builder;

import java.util.List;

@Builder
public record EdiInterchange(
        String senderId,       // ISA06 (trimmed)
        String receiverId,     // ISA08 (trimmed)
        String date,           // ISA09
        String time,           // ISA10
        String version,        // ISA12 (raw 5-char, e.g. "00401")
        String controlNumber,  // ISA13
        List<EdiGroup> groups
) {}