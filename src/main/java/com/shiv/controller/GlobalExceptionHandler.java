package com.shiv.controller;

import com.shiv.dtos.EdiValidationError;
import com.shiv.dtos.EdiValidationResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

import java.util.List;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<EdiValidationResponse> handleMissingParam(MissingServletRequestParameterException ex) {
        log.warn("Missing request parameter: {}", ex.getParameterName());
        return ResponseEntity.badRequest().body(errorResponse(
                "MISSING_PARAMETER",
                "Required parameter '" + ex.getParameterName() + "' is missing.",
                HttpStatus.BAD_REQUEST));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<EdiValidationResponse> handleMaxUploadSize(MaxUploadSizeExceededException ex) {
        log.warn("Upload size exceeded: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(errorResponse(
                "FILE_TOO_LARGE",
                "Uploaded file exceeds the maximum allowed size.",
                HttpStatus.PAYLOAD_TOO_LARGE));
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<EdiValidationResponse> handleMultipart(MultipartException ex) {
        log.warn("Multipart error: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(errorResponse(
                "INVALID_MULTIPART_REQUEST",
                "Invalid multipart request: " + ex.getMessage(),
                HttpStatus.BAD_REQUEST));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<EdiValidationResponse> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Illegal argument: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(errorResponse(
                "INVALID_INPUT",
                ex.getMessage(),
                HttpStatus.BAD_REQUEST));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<EdiValidationResponse> handleUnexpected(Exception ex) {
        log.error("Unhandled exception: {}", ex.getMessage(), ex);
        return ResponseEntity.internalServerError().body(errorResponse(
                ex.getClass().getSimpleName(),
                "An unexpected server error occurred. Please try again or contact support.",
                HttpStatus.INTERNAL_SERVER_ERROR));
    }

    private EdiValidationResponse errorResponse(String errorType, String message, HttpStatus status) {
        return EdiValidationResponse.builder()
                .valid(false)
                .totalErrors(1)
                .validationMode("ERROR")
                .summary("Request failed: " + message)
                .warnings(List.of())
                .errors(List.of(EdiValidationError.builder()
                        .eventType("REQUEST_ERROR")
                        .errorType(errorType)
                        .segmentTag("N/A")
                        .segmentPosition(-1)
                        .elementPosition(-1)
                        .componentPosition(-1)
                        .value(message)
                        .build()))
                .build();
    }
}