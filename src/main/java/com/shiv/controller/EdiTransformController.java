package com.shiv.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shiv.dtos.parse.EdiDocument;
import com.shiv.dtos.parse.EdiParseResult;
import com.shiv.dtos.parse.EdiTransaction;
import com.shiv.service.EdiParserService;
import com.shiv.transform.EdiTransformResult;
import com.shiv.transform.FlatFileSerializer;
import com.shiv.transform.RuleBasedTransformer;
import com.shiv.transform.model.MappingRule;
import com.shiv.transform.model.MappingRuleSet;
import com.shiv.transform.model.OutputFormat;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/edi")
@RequiredArgsConstructor
public class EdiTransformController {

    private final EdiParserService parserService;
    private final RuleBasedTransformer transformer;
    private final FlatFileSerializer flatFileSerializer;
    private final ObjectMapper objectMapper;

    /**
     * POST /api/edi/transform
     * AUTO mode — auto-detect document type, parse, then apply mapping rules.
     *
     * <p>Multipart form fields:
     * <ul>
     *   <li>{@code file}  — raw EDI file bytes</li>
     *   <li>{@code rules} — JSON string of {@link MappingRuleSet}</li>
     * </ul>
     *
     * Response 200  : { transformed: true,  transactionCode, outputFormat, businessJson | flatFile, validation }
     * Response 422  : { transformed: false, validation: { errors: [...] } }
     */
    @PostMapping(value = "/transform", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<EdiTransformResult> transformAuto(
            @RequestParam("file")  MultipartFile file,
            @RequestParam("rules") String rulesJson) {
        validateNotEmpty(file);
        MappingRuleSet rules = parseRules(rulesJson);
        validateRuleSet(rules);
        return toResponse(execute(readBytes(file), rules, null, null));
    }

    /**
     * POST /api/edi/transform/manual?version=004010&transaction=850
     * MANUAL mode — caller specifies the EDI version and transaction code.
     */
    @PostMapping(value = "/transform/manual", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<EdiTransformResult> transformManual(
            @RequestParam("file")  MultipartFile file,
            @RequestParam("rules") String rulesJson,
            @RequestParam(value = "version",     defaultValue = "004010") String version,
            @RequestParam(value = "transaction", defaultValue = "850")    String transaction) {
        validateNotEmpty(file);
        MappingRuleSet rules = parseRules(rulesJson);
        validateRuleSet(rules);
        return toResponse(execute(readBytes(file), rules, version, transaction));
    }

    // ── Core execution ────────────────────────────────────────────────────────

    private EdiTransformResult execute(byte[] ediBytes, MappingRuleSet rules,
                                       String version, String transaction) {
        EdiParseResult parseResult = (version != null)
                ? parserService.parseManual(ediBytes, version, transaction)
                : parserService.parseAuto(ediBytes);

        if (!parseResult.parsed()) {
            return new EdiTransformResult(false, null, null, null, null, parseResult.validation());
        }

        EdiDocument doc = parseResult.document();
        EdiTransaction tx = doc.interchange().groups().stream()
                .flatMap(g -> g.transactions().stream())
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No transactions found in the EDI document."));

        Map<String, Object> businessJson = transformer.transform(tx, rules.rules());
        OutputFormat format = rules.effectiveOutputFormat();

        String flatFile = null;
        if (format == OutputFormat.FLAT_FILE) {
            if (rules.flatFileConfig() == null) {
                throw new IllegalArgumentException(
                        "flatFileConfig is required when outputFormat is FLAT_FILE.");
            }
            flatFile = flatFileSerializer.serialize(businessJson, rules.flatFileConfig());
        }

        log.info("Transform OK: txCode={} format={} topLevelFields={}",
                tx.transactionCode(), format, businessJson.size());

        return new EdiTransformResult(
                true,
                tx.transactionCode(),
                format.name(),
                format == OutputFormat.BUSINESS_JSON ? businessJson : null,
                flatFile,
                parseResult.validation()
        );
    }

    // ── Validation ────────────────────────────────────────────────────────────

    private MappingRuleSet parseRules(String rulesJson) {
        try {
            return objectMapper.readValue(rulesJson, MappingRuleSet.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid mapping rules JSON: " + e.getMessage());
        }
    }

    private void validateRuleSet(MappingRuleSet rules) {
        if (rules.rules() == null || rules.rules().isEmpty()) {
            throw new IllegalArgumentException("Mapping rules must not be null or empty.");
        }
        for (int i = 0; i < rules.rules().size(); i++) {
            validateRule(rules.rules().get(i), i);
        }
    }

    private void validateRule(MappingRule rule, int index) {
        String ctx = "rules[" + index + "]";
        if (rule.type() == null) {
            throw new IllegalArgumentException(ctx + ": 'type' is required " +
                    "(SEGMENT_FIELD, SEGMENT_FIELD_QUALIFIED, LOOP_FIELD, LOOP_ARRAY).");
        }
        switch (rule.type()) {
            case SEGMENT_FIELD -> {
                if (blank(rule.segment()) || rule.position() == null || blank(rule.field()))
                    throw new IllegalArgumentException(
                            ctx + " (SEGMENT_FIELD): 'segment', 'position', and 'field' are required.");
            }
            case SEGMENT_FIELD_QUALIFIED -> {
                if (blank(rule.segment()) || rule.filterPosition() == null || blank(rule.filterValue())
                        || rule.position() == null || blank(rule.field()))
                    throw new IllegalArgumentException(
                            ctx + " (SEGMENT_FIELD_QUALIFIED): 'segment', 'filterPosition', " +
                                  "'filterValue', 'position', and 'field' are required.");
            }
            case LOOP_FIELD -> {
                if (blank(rule.loopTrigger()) || blank(rule.segment()) || rule.position() == null || blank(rule.field()))
                    throw new IllegalArgumentException(
                            ctx + " (LOOP_FIELD): 'loopTrigger', 'segment', 'position', and 'field' are required.");
            }
            case LOOP_ARRAY -> {
                if (blank(rule.loopTrigger()) || blank(rule.arrayField())
                        || rule.itemRules() == null || rule.itemRules().isEmpty())
                    throw new IllegalArgumentException(
                            ctx + " (LOOP_ARRAY): 'loopTrigger', 'arrayField', and non-empty 'itemRules' are required.");
            }
            case LOOP_FIELD_NESTED -> {
                if (rule.loopChain() == null || rule.loopChain().isEmpty())
                    throw new IllegalArgumentException(
                            ctx + " (LOOP_FIELD_NESTED): 'loopChain' must not be empty.");
                if (blank(rule.segment()) || rule.position() == null || blank(rule.field()))
                    throw new IllegalArgumentException(
                            ctx + " (LOOP_FIELD_NESTED): 'segment', 'position', and 'field' are required.");
            }
            case LOOP_ARRAY_NESTED -> {
                if (rule.loopChain() == null || rule.loopChain().isEmpty())
                    throw new IllegalArgumentException(
                            ctx + " (LOOP_ARRAY_NESTED): 'loopChain' must not be empty.");
                if (blank(rule.loopTrigger()) || blank(rule.arrayField())
                        || rule.itemRules() == null || rule.itemRules().isEmpty())
                    throw new IllegalArgumentException(
                            ctx + " (LOOP_ARRAY_NESTED): 'loopTrigger', 'arrayField', and non-empty 'itemRules' are required.");
            }
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    private ResponseEntity<EdiTransformResult> toResponse(EdiTransformResult result) {
        return result.transformed()
                ? ResponseEntity.ok(result)
                : ResponseEntity.unprocessableEntity().body(result);
    }

    private void validateNotEmpty(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException(
                    "No file provided or file is empty. Please upload a valid EDI document.");
        }
    }

    private byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (Exception e) {
            log.error("Failed to read uploaded file: {}", e.getMessage(), e);
            throw new IllegalArgumentException("Failed to read the uploaded file: " + e.getMessage());
        }
    }
}
