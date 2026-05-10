package com.shiv.registry;


import com.shiv.config.EdiSchemaProperties;
import io.xlate.edi.schema.Schema;
import io.xlate.edi.schema.SchemaFactory;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class EdiSchemaRegistry {

    private final EdiSchemaProperties properties;

    // In-memory cache: "004010-850" → Schema object
    private final Map<String, Schema> schemaCache = new HashMap<>();

    // ─────────────────────────────────────────────
    // Load ALL schemas at startup — fail fast
    // ─────────────────────────────────────────────
    @PostConstruct
    public void loadAllSchemas() {
        log.info("Loading EDI schema registry — {} entries configured", properties.getSchemaRegistry().size());

        SchemaFactory factory = SchemaFactory.newFactory();

        properties.getSchemaRegistry().forEach((key, path) -> {
            try {
                URL url = getClass().getClassLoader().getResource(path);
                if (url == null) {
                    log.warn("Schema not found for key [{}] at path: {}", key, path);
                    return;
                }
                Schema schema = factory.createSchema(url);
                schemaCache.put(key, schema);
                log.info("Loaded schema [{}] from {}", key, path);

            } catch (Exception e) {
                log.error("Failed to load schema [{}] from {}: {}", key, path, e.getMessage());
            }
        });

        log.info("Schema registry ready — {}/{} schemas loaded", schemaCache.size(), properties.getSchemaRegistry().size());
    }


    // Lookup by version + transaction
    public Optional<Schema> getSchema(String version, String transaction) {
        String key = version + "-" + transaction;
        Schema schema = schemaCache.get(key);
        if (schema == null) {
            log.warn("No schema found in registry for key: {}", key);
        }
        return Optional.ofNullable(schema);
    }

    // ─────────────────────────────────────────────
    // List all registered schemas
    // ─────────────────────────────────────────────
    public Map<String, Boolean> getRegistryStatus() {
        Map<String, Boolean> status = new HashMap<>();
        properties.getSchemaRegistry().keySet().forEach(key -> status.put(key, schemaCache.containsKey(key)));
        return status;
    }
}