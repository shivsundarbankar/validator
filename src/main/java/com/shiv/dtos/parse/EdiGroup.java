package com.shiv.dtos.parse;

import lombok.Builder;

import java.util.List;

@Builder
public record EdiGroup(
        String functionalIdentifier,  // GS01 (e.g. "PO", "FA", "HC")
        String applicationSender,     // GS02
        String applicationReceiver,   // GS03
        String date,                  // GS04 (CCYYMMDD)
        String time,                  // GS05 (HHMM)
        String controlNumber,         // GS06
        List<EdiTransaction> transactions
) {}