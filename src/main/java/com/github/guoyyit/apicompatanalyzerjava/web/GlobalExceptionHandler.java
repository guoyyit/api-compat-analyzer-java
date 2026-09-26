package com.github.guoyyit.apicompatanalyzerjava.web;

import com.github.guoyyit.apicompatanalyzerjava.parse.AnalysisBudgetException;
import com.github.guoyyit.apicompatanalyzerjava.service.AnalysisRejectedException;
import com.github.guoyyit.apicompatanalyzerjava.service.AnalysisTimeoutException;
import com.github.guoyyit.apicompatanalyzerjava.web.dto.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.UncheckedIOException;

/** Maps domain failures to stable HTTP status codes without leaking partial reports. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(AnalysisTimeoutException.class)
    public ResponseEntity<ErrorResponse> handleTimeout(AnalysisTimeoutException e) {
        return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                .body(new ErrorResponse("ANALYSIS_TIMEOUT", e.getMessage()));
    }

    @ExceptionHandler(AnalysisBudgetException.class)
    public ResponseEntity<ErrorResponse> handleBudget(AnalysisBudgetException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ErrorResponse("RESOURCE_LIMIT_EXCEEDED", e.getMessage()));
    }

    @ExceptionHandler(AnalysisRejectedException.class)
    public ResponseEntity<ErrorResponse> handleRejected(AnalysisRejectedException e) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("ANALYSIS_REJECTED", e.getMessage()));
    }

    @ExceptionHandler(UncheckedIOException.class)
    public ResponseEntity<ErrorResponse> handleUncheckedIo(UncheckedIOException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("SOURCE_READ_FAILED", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("INVALID_SOURCE", e.getMessage()));
    }
}
