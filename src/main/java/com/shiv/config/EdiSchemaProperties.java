package com.shiv.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "edi")
public class EdiSchemaProperties {

    // key = "004010-850", value = "schemas/x12/004010/850.xml"
    private Map<String, String> schemaRegistry = new HashMap<>();

    private boolean fallbackToStructural = true;

    // Build key from version + transaction
    public String getSchemaPath(String version, String transaction) {
        return schemaRegistry.get(version + "-" + transaction);
    }
}