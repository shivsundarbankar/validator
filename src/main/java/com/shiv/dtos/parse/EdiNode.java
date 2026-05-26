package com.shiv.dtos.parse;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "nodeType")
@JsonSubTypes({
        @JsonSubTypes.Type(value = EdiLoop.class,    name = "LOOP"),
        @JsonSubTypes.Type(value = EdiSegment.class, name = "SEGMENT")
})
public interface EdiNode {}