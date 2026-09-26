package com.github.guoyyit.apicompatanalyzerjava.web;

import com.github.guoyyit.apicompatanalyzerjava.service.AnalysisException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** Maps analysis failures to error responses; no partial report is exposed. */
@RestControllerAdvice
public class AnalysisExceptionHandler {

    @ExceptionHandler(AnalysisException.class)
    public ResponseEntity<Map<String, Object>> handle(AnalysisException ex) {
        HttpStatus status = ex.getMessage() != null && ex.getMessage().contains("timed out")
                ? HttpStatus.GATEWAY_TIMEOUT : HttpStatus.UNPROCESSABLE_ENTITY;
        return ResponseEntity.status(status).body(Map.of(
                "error", ex.getMessage() == null ? "analysis failed" : ex.getMessage(),
                "reportPublished", false));
    }
}
