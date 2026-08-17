package com.orderfulfillment.monolith.common;

import org.springframework.http.HttpStatus;

public class ValidationApiException extends ApiException {
    public ValidationApiException(String code, String message) {
        super(HttpStatus.BAD_REQUEST, code, message);
    }
}
