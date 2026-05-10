package com.shiv.controller;

import com.shiv.dtos.EdiValidationResponse;
import com.shiv.service.EdiValidationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@Slf4j
@RestController
@RequestMapping("/api/edi")
@RequiredArgsConstructor
public class EdiValidationController {

    private final EdiValidationService ediValidationService;

    /**
     * POST /api/edi/validate/structure
     * Structural validation only — no schema
     */
    @PostMapping(value = "/validate/structure",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<EdiValidationResponse> validateStructure(
            @RequestParam("file") MultipartFile file
    ) throws IOException {
        log.info("Received EDI file for structural validation: {}", file.getOriginalFilename());
        EdiValidationResponse response = ediValidationService.validateStructure(file.getInputStream());
        return ResponseEntity.ok(response);
    }

    /**
     * POST /api/edi/validate/builtin?version=005010&transaction=850
     * Validate using StAEDI built-in schema
     */
    @PostMapping(value = "/validate/builtin",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<EdiValidationResponse> validateWithBuiltin(
            @RequestParam("file")        MultipartFile file,
            @RequestParam("version")     String version,      // e.g. 004010
            @RequestParam("transaction") String transaction   // e.g. 850
    ) throws IOException {
        log.info("Received EDI file: {} | schema: {}/{}", file.getOriginalFilename(), version, transaction);
        EdiValidationResponse response = ediValidationService
                .validateWithBuiltinSchema(file.getInputStream(), version, transaction);
        return ResponseEntity.ok(response);
    }

    /**
     * POST /api/edi/validate/custom?schemaPath=schemas/x12/004010/850.xml
     * Validate using custom XML schema from classpath
     */
    @PostMapping(value = "/validate/custom",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<EdiValidationResponse> validateWithCustom(
            @RequestParam("file")       MultipartFile file,
            @RequestParam("schemaPath") String schemaPath    // e.g. schemas/x12/004010/850.xml
    ) throws IOException {
        log.info("Received EDI file: {} | custom schema: {}", file.getOriginalFilename(), schemaPath);
        EdiValidationResponse response = ediValidationService
                .validateWithCustomSchema(file.getInputStream(), schemaPath);
        return ResponseEntity.ok(response);
    }
}