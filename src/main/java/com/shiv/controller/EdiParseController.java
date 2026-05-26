package com.shiv.controller;

import com.shiv.dtos.parse.EdiParseResult;
import com.shiv.service.EdiParserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@RestController
@RequestMapping("/api/edi")
@RequiredArgsConstructor
public class EdiParseController {

    private final EdiParserService parserService;

    /**
     * POST /api/edi/parse
     * AUTO mode — detects document type from ISA/ST, validates, then parses.
     *
     * Response 200: { parsed: true,  document: {...}, validation: {...} }
     * Response 422: { parsed: false, document: null,  validation: { errors: [...] } }
     */
    @PostMapping(value = "/parse", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<EdiParseResult> parseAuto(@RequestParam("file") MultipartFile file) {
        validateNotEmpty(file);
        EdiParseResult result = parserService.parseAuto(readBytes(file));
        return toResponse(result);
    }

    /**
     * POST /api/edi/parse/manual?version=004010&transaction=850
     * MANUAL mode — caller specifies version and transaction, validates, then parses.
     */
    @PostMapping(value = "/parse/manual", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<EdiParseResult> parseManual(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "version",     defaultValue = "004010") String version,
            @RequestParam(value = "transaction", defaultValue = "850")    String transaction) {
        validateNotEmpty(file);
        EdiParseResult result = parserService.parseManual(readBytes(file), version, transaction);
        return toResponse(result);
    }

    private ResponseEntity<EdiParseResult> toResponse(EdiParseResult result) {
        return result.parsed()
                ? ResponseEntity.ok(result)
                : ResponseEntity.unprocessableEntity().body(result);
    }

    private void validateNotEmpty(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("No file provided or file is empty. Please upload a valid EDI document.");
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