package com.shiv.service;


import com.shiv.dtos.EdiValidationError;
import com.shiv.dtos.EdiValidationResponse;
import com.shiv.registry.EdiSchemaRegistry;
import io.xlate.edi.schema.Schema;
import io.xlate.edi.stream.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class EdiValidationService {

    private static final Set<EDIStreamEvent> TEXT_SAFE_EVENTS = Set.of(
            EDIStreamEvent.ELEMENT_DATA,
            EDIStreamEvent.ELEMENT_DATA_ERROR,
            EDIStreamEvent.START_SEGMENT,
            EDIStreamEvent.END_SEGMENT);

    private final EdiSchemaRegistry schemaRegistry;
    private final EdiDocumentDetector detector;


    // AUTO — detect transaction from document, pick schema, validate
    public EdiValidationResponse validateAuto(byte[] ediBytes) {

        // Pre-flight structural check first
        List<EdiValidationError> preflightErrors = preflightCheck(ediBytes);
        if (!preflightErrors.isEmpty()) {
            return EdiValidationResponse
                    .builder()
                    .valid(false)
                    .totalErrors(preflightErrors.size()).summary("EDI document is INVALID — %d structural error(s) found.".formatted(preflightErrors.size()))
                    .errors(preflightErrors)
                    .build();
        }

        try {
            EdiDocumentDetector.EdiDocumentInfo info = detector.detect(new ByteArrayInputStream(ediBytes));

            Optional<Schema> schema = schemaRegistry.getSchema(info.version(), info.transaction());

            if (schema.isEmpty()) {
                log.warn("No schema for {}-{}. Falling back to structural.", info.version(), info.transaction());
            }

            return doValidate(new ByteArrayInputStream(ediBytes), schema.orElse(null));

        } catch (Exception e) {
            log.error("Auto-detection failed: {}", e.getMessage(), e);
            return EdiValidationResponse.builder().valid(false).totalErrors(1).summary("Failed to detect EDI document type.").errors(List.of(EdiValidationError.builder().eventType("DETECTION_ERROR").errorType(e.getClass().getSimpleName()).segmentTag("N/A").segmentPosition(-1).elementPosition(-1).componentPosition(-1).value(e.getMessage()).build())).build();
        }
    }


    // MANUAL
    public EdiValidationResponse validateManual(byte[] ediBytes, String version, String transaction) {
        List<EdiValidationError> preflightErrors = preflightCheck(ediBytes);
        if (!preflightErrors.isEmpty()) {
            return EdiValidationResponse.builder().valid(false).totalErrors(preflightErrors.size()).summary("EDI document is INVALID — %d structural error(s) found.".formatted(preflightErrors.size())).errors(preflightErrors).build();
        }

        Optional<Schema> schema = schemaRegistry.getSchema(version, transaction);
        return doValidate(new ByteArrayInputStream(ediBytes), schema.orElse(null));
    }


    // STRUCTURAL only
    public EdiValidationResponse validateStructure(byte[] ediBytes) {
        List<EdiValidationError> preflightErrors = preflightCheck(ediBytes);
        if (!preflightErrors.isEmpty()) {
            return EdiValidationResponse.builder().valid(false).totalErrors(preflightErrors.size()).summary("EDI document is INVALID — %d structural error(s) found.".formatted(preflightErrors.size())).errors(preflightErrors).build();
        }
        return doValidate(new ByteArrayInputStream(ediBytes), null);
    }


    // PRE-FLIGHT CHECK
    // Validates basic structure BEFORE passing to StAEDI reader
    // Produces clear human-readable errors instead of EDIE003
    private List<EdiValidationError> preflightCheck(byte[] ediBytes) {
        List<EdiValidationError> errors = new ArrayList<>();

        // 1. Empty file check
        if (ediBytes == null || ediBytes.length == 0) {
            errors.add(preflightError("EMPTY_FILE", "EDI file is empty. Please upload a valid EDI document."));
            return errors; // no point continuing
        }

        String content = new String(ediBytes, StandardCharsets.UTF_8).trim();

        // 2. Minimum length check
        if (content.length() < 106) {
            errors.add(preflightError("FILE_TOO_SHORT", "File is too short (%d chars). A valid EDI document must start with ".formatted(content.length()) + "an ISA segment of exactly 106 characters."));
            return errors;
        }

        // 3. Must start with ISA
        if (!content.startsWith("ISA")) {
            String firstChars = content.substring(0, Math.min(20, content.length()));
            errors.add(preflightError("MISSING_ISA_SEGMENT", "EDI document must start with 'ISA' but found: '" + firstChars + "...'. " + "Ensure the Interchange Control Header (ISA) is the first segment."));
            return errors;
        }

        // 4. Detect element delimiter from ISA03 (position 3)
        char elementDelimiter = content.charAt(3);
        if (elementDelimiter == '\n' || elementDelimiter == '\r' || elementDelimiter == ' ') {
            errors.add(preflightError("INVALID_ELEMENT_DELIMITER", "Invalid element delimiter '" + elementDelimiter + "' detected at ISA position 3. " + "Common delimiters are '*' or '|'."));
            return errors;
        }

        // 5. ISA must be exactly 106 chars before segment terminator
        // Split ISA elements by delimiter to count them
        String isaSegment = content.substring(0, Math.min(200, content.length()));
        char segTerminator = detectSegmentTerminator(isaSegment, elementDelimiter);

        int isaEnd = isaSegment.indexOf(segTerminator);
        if (isaEnd < 0) {
            errors.add(preflightError("MISSING_SEGMENT_TERMINATOR", "Could not find segment terminator after ISA segment. " + "Common terminators are '~' or newline."));
            return errors;
        }

        // 6. ISA must have exactly 16 elements (ISA01 through ISA16)
        String[] isaElements = content.substring(0, isaEnd).split("\\" + elementDelimiter);
        if (isaElements.length != 17) { // "ISA" + 16 elements = 17 parts
            errors.add(preflightError("INVALID_ISA_ELEMENT_COUNT", "ISA segment must have exactly 16 elements but found " + (isaElements.length - 1) + ". " + "Check ISA segment format: ISA*00*[10 spaces]*00*[10 spaces]*ZZ*SENDER[9 spaces]*ZZ*RECEIVER[8 spaces]*date*time*U/^*version*ctrl*ack*T/P*:~"));
        }

        // 7. Check GS segment exists
        if (!content.contains("GS" + elementDelimiter)) {
            errors.add(preflightError("MISSING_GS_SEGMENT", "Functional Group Header (GS) segment is missing. " + "GS segment must follow the ISA segment."));
        }

        // 8. Check ST segment exists
        if (!content.contains("ST" + elementDelimiter)) {
            errors.add(preflightError("MISSING_ST_SEGMENT", "Transaction Set Header (ST) segment is missing. " + "ST segment must be present inside each functional group."));
        }

        // 9. Check SE segment exists
        if (!content.contains("SE" + elementDelimiter)) {
            errors.add(preflightError("MISSING_SE_SEGMENT", "Transaction Set Trailer (SE) segment is missing. " + "Every ST segment must have a matching SE segment."));
        }

        // 10. Check IEA segment exists
        if (!content.contains("IEA" + elementDelimiter)) {
            errors.add(preflightError("MISSING_IEA_SEGMENT", "Interchange Control Trailer (IEA) segment is missing. " + "Every ISA segment must have a matching IEA segment."));
        }

        return errors;
    }

    // Detect segment terminator — it's the char right after ISA16 (component sep)
    private char detectSegmentTerminator(String isaSegment, char elementDelimiter) {
        // ISA is always ISA + 16 elements = 106 chars total
        // The 106th char is the segment terminator
        if (isaSegment.length() >= 106) {
            return isaSegment.charAt(105);
        }
        // fallback — look for common terminators
        for (char c : new char[]{'~', '\n', '|', '!'}) {
            if (isaSegment.indexOf(c) > 0) return c;
        }
        return '~';
    }

    private EdiValidationError preflightError(String errorType, String message) {
        EdiValidationError error = EdiValidationError.builder().eventType("STRUCTURE_ERROR").errorType(errorType).segmentTag("N/A").segmentPosition(-1).elementPosition(-1).componentPosition(-1).value(message).build();
        return error;
    }

    // =========================================================
    // CORE VALIDATION (unchanged — StAEDI reader loop)
    // =========================================================
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
                    case START_INTERCHANGE -> log.info("▶ START_INTERCHANGE segment={}", loc.getSegmentPosition());

                    case START_GROUP -> log.info("  ▶ START_GROUP segment={}", loc.getSegmentPosition());

                    case START_TRANSACTION -> {
                        log.info("    ▶ START_TRANSACTION [{}] segment={}", reader.getReferenceCode(), loc.getSegmentPosition());
                        if (schema != null) {
                            reader.setTransactionSchema(schema);
                        }
                    }

                    case START_SEGMENT ->
                            log.debug("      SEGMENT [{}] pos={}", reader.getText(), loc.getSegmentPosition());

                    case ELEMENT_DATA ->
                            log.debug("        ELEMENT [{}] elem={}", reader.getText(), loc.getElementPosition());

                    case END_TRANSACTION -> log.info("    ◀ END_TRANSACTION segment={}", loc.getSegmentPosition());

                    case END_GROUP -> log.info("  ◀ END_GROUP");
                    case END_INTERCHANGE -> log.info("◀ END_INTERCHANGE");

                    case SEGMENT_ERROR -> {
                        EdiValidationError err = buildError("SEGMENT_ERROR", reader.getErrorType().toString(), loc, null);
                        errors.add(err);
                    }

                    case ELEMENT_DATA_ERROR -> {
                        EdiValidationError err = buildError("ELEMENT_DATA_ERROR", reader.getErrorType().toString(), loc, safeGetText(reader, event));
                        errors.add(err);
                    }

                    case ELEMENT_OCCURRENCE_ERROR -> {
                        EdiValidationError err = buildError("ELEMENT_OCCURRENCE_ERROR", reader.getErrorType().toString(), loc, safeGetText(reader, event));
                        errors.add(err);
                    }

                    default -> {
                    }
                }
            }

        } catch (EDIStreamException e) {
            // Translate EDIE003 and other StAEDI internal errors to human-readable form
            String friendlyMessage = translateEdiException(e.getMessage());
            log.error("EDI stream error: {}", e.getMessage());
            errors.add(buildError("PARSE_ERROR", "EDI_PARSE_FAILED", friendlyMessage));

        } catch (Exception e) {
            log.error("Unexpected error: {}", e.getMessage(), e);
            errors.add(fatalError(e.getClass().getSimpleName(), e.getMessage()));

        } finally {
            if (reader != null) try {
                reader.close();
            } catch (Exception ignored) {
            }
        }

        boolean valid = errors.isEmpty();
        String summary = valid ? "EDI document is VALID — no errors found." : "EDI document is INVALID — %d error(s) found.".formatted(errors.size());

        log.info("Result: {}", summary);
        return EdiValidationResponse.builder().valid(valid).totalErrors(errors.size()).summary(summary).errors(errors).build();
    }

    // Translate cryptic StAEDI error codes to clear messages
    private String translateEdiException(String rawMessage) {
        if (rawMessage == null) return "Unknown EDI parsing error.";

        if (rawMessage.contains("EDIE003")) {
            return "Invalid EDI structure at start of document. " + "Ensure the file starts with a valid ISA segment " + "and uses the correct element delimiter (e.g. '*').";
        }
        if (rawMessage.contains("EDIE001")) {
            return "Unexpected end of EDI document. " + "The file may be truncated or incomplete.";
        }
        if (rawMessage.contains("EDIE002")) {
            return "Invalid character found in EDI document. " + "Check for hidden characters or wrong file encoding.";
        }
        // fallback — return raw but cleaned up
        return "EDI parsing failed: " + rawMessage;
    }


    // HELPERS
    private EdiValidationError buildError(String eventType, String errorType, Location loc, String value) {
        return EdiValidationError.builder().eventType(eventType).errorType(errorType).segmentTag(loc.getSegmentTag()).segmentPosition(loc.getSegmentPosition()).elementPosition(loc.getElementPosition()).componentPosition(loc.getComponentPosition()).value(value).build();
    }

    private EdiValidationError buildError(String eventType, String errorType, String value) {
        return EdiValidationError.builder().eventType(eventType).errorType(errorType).segmentTag("N/A").segmentPosition(-1).elementPosition(-1).componentPosition(-1).value(value).build();
    }

    private EdiValidationError fatalError(String type, String message) {
        return EdiValidationError.builder().eventType("FATAL_ERROR").errorType(type).segmentTag("N/A").segmentPosition(-1).elementPosition(-1).componentPosition(-1).value(message).build();
    }

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
}