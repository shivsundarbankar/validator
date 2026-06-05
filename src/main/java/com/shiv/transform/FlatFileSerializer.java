package com.shiv.transform;

import com.shiv.transform.model.FlatFileConfig;
import com.shiv.transform.model.RecordTypeConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Serializes the business-JSON map produced by {@link RuleBasedTransformer}
 * into a delimited flat file.
 *
 * <p>Record types with no {@code sourceArray} are emitted once (header / trailer).
 * Record types with a {@code sourceArray} are emitted once per item in that array.
 */
@Slf4j
@Component
public class FlatFileSerializer {

    public String serialize(Map<String, Object> businessJson, FlatFileConfig config) {
        String delim = config.effectiveDelimiter();
        StringBuilder sb = new StringBuilder();

        for (RecordTypeConfig rec : config.recordTypes()) {
            if (rec.sourceArray() == null) {
                sb.append(buildRecord(rec.prefix(), rec.fields(), businessJson, delim)).append('\n');
            } else {
                Object arrayVal = resolveField(businessJson, rec.sourceArray());
                if (arrayVal instanceof List<?> list) {
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> itemMap) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> typed = (Map<String, Object>) itemMap;
                            sb.append(buildRecord(rec.prefix(), rec.fields(), typed, delim)).append('\n');
                        }
                    }
                } else {
                    log.debug("FlatFile: sourceArray '{}' not found or not a list", rec.sourceArray());
                }
            }
        }

        return sb.toString().stripTrailing();
    }

    private String buildRecord(String prefix, List<String> fields,
                                Map<String, Object> source, String delim) {
        StringBuilder record = new StringBuilder(prefix);
        for (String field : fields) {
            Object val = resolveField(source, field);
            record.append(delim).append(val != null ? val.toString() : "");
        }
        return record.toString();
    }

    /** Resolves a dot-notation path against a nested map. */
    @SuppressWarnings("unchecked")
    private Object resolveField(Map<String, Object> source, String path) {
        String[] parts = path.split("\\.", 2);
        Object val = source.get(parts[0]);
        if (parts.length == 1 || !(val instanceof Map<?, ?>)) return val;
        return resolveField((Map<String, Object>) val, parts[1]);
    }
}
