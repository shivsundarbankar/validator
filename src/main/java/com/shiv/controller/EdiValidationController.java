package com.shiv.controller;

import com.shiv.dtos.EdiValidationResponse;
import com.shiv.registry.EdiSchemaRegistry;
import com.shiv.service.EdiValidationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/edi")
@RequiredArgsConstructor
public class EdiValidationController {

    private final EdiValidationService ediValidationService;
    private final EdiSchemaRegistry schemaRegistry;

    /**
     * POST /api/edi/validate
     * AUTO mode — detects transaction type from document itself
     * No params needed — just upload the file
     */
    @PostMapping(value = "/validate", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<EdiValidationResponse> validateAuto(@RequestParam("file") MultipartFile file) throws IOException {
        return ResponseEntity.ok(ediValidationService.validateAuto(file.getBytes()));
    }

    /**
     * POST /api/edi/validate/manual?version=004010&transaction=850
     * MANUAL mode — caller specifies version and transaction
     */
    @PostMapping(value = "/validate/manual", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<EdiValidationResponse> validateManual(@RequestParam("file") MultipartFile file, @RequestParam(value = "version", defaultValue = "004010") String version, @RequestParam(value = "transaction", defaultValue = "850") String transaction) throws IOException {
        return ResponseEntity.ok(ediValidationService.validateManual(file.getBytes(), version, transaction));

    }

    /**
     * POST /api/edi/validate/structure
     * STRUCTURAL only — no schema, envelope check only
     */
    @PostMapping(value = "/validate/structure", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<EdiValidationResponse> validateStructure(@RequestParam("file") MultipartFile file) throws IOException {
        return ResponseEntity.ok(ediValidationService.validateStructure(file.getBytes()));
    }

    /**
     * GET /api/edi/schemas
     * Shows all registered schemas and their load status
     */
    @GetMapping("/schemas")
    public ResponseEntity<Map<String, Boolean>> listSchemas() {
        return ResponseEntity.ok(schemaRegistry.getRegistryStatus());
    }
}