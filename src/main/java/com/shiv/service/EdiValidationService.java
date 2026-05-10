package com.shiv.service;

import com.shiv.dtos.EdiValidationError;
import com.shiv.dtos.EdiValidationResponse;
import io.xlate.edi.schema.Schema;
import io.xlate.edi.schema.SchemaFactory;
import io.xlate.edi.stream.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
public class EdiValidationService {

    private static final Set<EDIStreamEvent> TEXT_SAFE_EVENTS = Set.of(EDIStreamEvent.ELEMENT_DATA, EDIStreamEvent.ELEMENT_DATA_ERROR, EDIStreamEvent.START_SEGMENT, EDIStreamEvent.END_SEGMENT);

    // Public API
    public EdiValidationResponse validateStructure(InputStream ediStream) {
        log.info("Starting structural EDI validation (no schema)");
        return doValidate(ediStream, null);
    }

    public EdiValidationResponse validateWithBuiltinSchema(InputStream ediStream, String version, String transaction) {
        log.info("Validating with built-in schema: {}/{}", version, transaction);
        Schema schema = loadBuiltinSchema(version, transaction);
        return doValidate(ediStream, schema);
    }

    public EdiValidationResponse validateWithCustomSchema(InputStream ediStream, String schemaPath) {
        log.info("Validating with custom schema: {}", schemaPath);
        Schema schema = loadCustomSchema(schemaPath);
        return doValidate(ediStream, schema);
    }


    private EdiValidationResponse doValidate(InputStream ediStream, Schema schema) {
        List<EdiValidationError> errors = new ArrayList<>();
        EDIStreamReader reader = null;

        try {
            EDIInputFactory factory = EDIInputFactory.newFactory();
            reader = factory.createEDIStreamReader(ediStream);

            while (reader.hasNext()) {
                EDIStreamEvent event = reader.next();
                Location loc = reader.getLocation();

                switch (event) {

                    case START_INTERCHANGE -> log.info("START_INTERCHANGE  segment={}", loc.getSegmentPosition());

                    case START_GROUP -> log.info("  START_GROUP  segment={}", loc.getSegmentPosition());

                    case START_TRANSACTION -> {
                        log.info("    START_TRANSACTION [{}]  segment={}", reader.getReferenceCode(), loc.getSegmentPosition());

                        // Attach schema HERE — only to the transaction body
                        // ISA / GS / ST / SE / GE / IEA are envelope segments
                        // and are validated by StAEDI internally — never by your schema
                        if (schema != null) {
                            reader.setTransactionSchema(schema);
                            log.info("      Schema attached to transaction");
                        }
                    }

                    case START_SEGMENT ->
                            log.info("      SEGMENT [{}]  pos={}", reader.getText(), loc.getSegmentPosition());

                    case ELEMENT_DATA ->
                            log.info("        ELEMENT val=[{}]  elem={}  comp={}", reader.getText(), loc.getElementPosition(), loc.getComponentPosition());

                    case END_TRANSACTION -> log.info("    END_TRANSACTION  segment={}", loc.getSegmentPosition());

                    case END_GROUP -> log.info("  ◀ END_GROUP");

                    case END_INTERCHANGE -> log.info("END_INTERCHANGE — done");


                    // ERROR EVENTS

                    case SEGMENT_ERROR -> {
                        EdiValidationError err = EdiValidationError.builder().eventType("SEGMENT_ERROR").errorType(reader.getErrorType().toString()).segmentTag(loc.getSegmentTag()).segmentPosition(loc.getSegmentPosition()).elementPosition(loc.getElementPosition()).componentPosition(loc.getComponentPosition()).value(null).build();
                        errors.add(err);
                        printError(err);
                    }

                    case ELEMENT_DATA_ERROR -> {
                        String badValue = safeGetText(reader, event);
                        EdiValidationError err = EdiValidationError.builder().eventType("ELEMENT_DATA_ERROR").errorType(reader.getErrorType().toString()).segmentTag(loc.getSegmentTag()).segmentPosition(loc.getSegmentPosition()).elementPosition(loc.getElementPosition()).componentPosition(loc.getComponentPosition()).value(badValue).build();
                        errors.add(err);
                        printError(err);
                    }

                    case ELEMENT_OCCURRENCE_ERROR -> {
                        String val = safeGetText(reader, event);
                        EdiValidationError err = EdiValidationError.builder().eventType("ELEMENT_OCCURRENCE_ERROR").errorType(reader.getErrorType().toString()).segmentTag(loc.getSegmentTag()).segmentPosition(loc.getSegmentPosition()).elementPosition(loc.getElementPosition()).componentPosition(loc.getComponentPosition()).value(val).build();
                        errors.add(err);
                        printError(err);
                    }

                    default -> { /* ignore */ }
                }
            }

        } catch (EDIStreamException e) {
            log.error("Fatal EDI parse error: {}", e.getMessage(), e);
            errors.add(fatalError("EDIStreamException", e.getMessage()));

        } catch (Exception e) {
            log.error("Unexpected error: {}", e.getMessage(), e);
            errors.add(fatalError(e.getClass().getSimpleName(), e.getMessage()));

        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception ignored) {
                }
            }
        }

        boolean valid = errors.isEmpty();
        String summary = valid ? "EDI document is VALID — no errors found." : "EDI document is INVALID — %d error(s) found.".formatted(errors.size());

        log.info("Result: {}", summary);

        return EdiValidationResponse.builder().valid(valid).totalErrors(errors.size()).summary(summary).errors(errors).build();
    }

    // SCHEMA LOADERS
    private Schema loadBuiltinSchema(String version, String transaction) {
        try {
            // StAEDI built-in schemas are inside the jar at /x12/{version}/{transaction}.xml
            String path = "/x12/%s/%s.xml".formatted(version, transaction);
            URL url = getClass().getResource(path);
            if (url == null) {
                log.warn("Built-in schema not found: {}", path);
                return null;
            }
            Schema schema = SchemaFactory.newFactory().createSchema(url);
            log.info("Loaded built-in schema: {}", path);
            return schema;
        } catch (Exception e) {
            log.error("Failed to load built-in schema {}/{}: {}", version, transaction, e.getMessage());
            return null;
        }
    }

    private Schema loadCustomSchema(String schemaPath) {
        try {
            // Place your schema at: src/main/resources/schemas/x12/004010/850.xml
            URL url = getClass().getClassLoader().getResource(schemaPath);
            if (url == null) {
                log.warn("Custom schema not found on classpath: {}", schemaPath);
                return null;
            }
            Schema schema = SchemaFactory.newFactory().createSchema(url);
            log.info("Loaded custom schema: {}", schemaPath);
            return schema;
        } catch (Exception e) {
            log.error("Failed to load custom schema {}: {}", schemaPath, e.getMessage());
            return null;
        }
    }


    // HELPERS
    private String safeGetText(EDIStreamReader reader, EDIStreamEvent event) {
        if (TEXT_SAFE_EVENTS.contains(event)) {
            try {
                return reader.getText();
            } catch (Exception e) {
                log.warn("getText() failed: {}", e.getMessage());
            }
        }
        return null;
    }

    private EdiValidationError fatalError(String type, String message) {
        return EdiValidationError.builder().eventType("FATAL_ERROR").errorType(type).segmentTag("N/A").segmentPosition(-1).elementPosition(-1).componentPosition(-1).value(message).build();
    }

    private void printError(EdiValidationError e) {
        System.err.printf("""
                ╔══════════════════════════════════════════════╗
                ║  EDI VALIDATION ERROR
                ║  Event    : %s
                ║  Error    : %s
                ║  Segment  : %s  (pos %d)
                ║  Element  : %d  Component: %d
                ║  Value    : %s
                ╚══════════════════════════════════════════════╝
                %n""", e.eventType(), e.errorType(), e.segmentTag(), e.segmentPosition(), e.elementPosition(), e.componentPosition(), e.value() != null ? e.value() : "N/A");
    }
}