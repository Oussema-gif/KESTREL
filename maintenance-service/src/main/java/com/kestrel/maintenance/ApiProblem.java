package com.kestrel.maintenance;

import org.springframework.http.HttpStatus;

final class ApiProblem extends RuntimeException {
    final HttpStatus status;
    final String code;
    final Long retryAfter;

    ApiProblem(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    private ApiProblem(HttpStatus status, String code, String message, Long retryAfter) {
        super(message);
        this.status = status;
        this.code = code;
        this.retryAfter = retryAfter;
    }

    static ApiProblem badRequest(String message) {
        return new ApiProblem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }

    static ApiProblem notFound(String message) {
        return new ApiProblem(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }

    static ApiProblem conflict(String message) {
        return new ApiProblem(HttpStatus.CONFLICT, "CONFLICT", message);
    }

    static ApiProblem forbidden() {
        return new ApiProblem(HttpStatus.FORBIDDEN, "FORBIDDEN", "You do not have permission for this operation.");
    }

    static ApiProblem rateLimited(long seconds) {
        return new ApiProblem(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many attempts. Try again later.", seconds);
    }
}
