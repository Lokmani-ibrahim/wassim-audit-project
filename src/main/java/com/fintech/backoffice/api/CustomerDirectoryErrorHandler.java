package com.fintech.backoffice.api;

import com.fintech.backoffice.CustomerNotFoundException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice(assignableTypes = CustomerDirectoryController.class)
public class CustomerDirectoryErrorHandler {
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalid() {
        return ResponseEntity.badRequest().body(Map.of("error", "INVALID_REQUEST"));
    }

    @ExceptionHandler(CustomerNotFoundException.class)
    ResponseEntity<Map<String, String>> missing() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "CUSTOMER_NOT_FOUND"));
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Map<String, String>> unavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", "CUSTOMER_DIRECTORY_UNAVAILABLE"));
    }
}
