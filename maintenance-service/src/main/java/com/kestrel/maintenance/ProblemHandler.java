package com.kestrel.maintenance;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
class ProblemHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ProblemHandler.class);

    private final AuditLog audit;

    ProblemHandler(AuditLog audit) {
        this.audit = audit;
    }

    record Violation(String field, String message) {}
    record ErrorBody(String code, String message, List<Violation> violations) {}

    @ExceptionHandler(ApiProblem.class)
    ResponseEntity<ErrorBody> problem(ApiProblem problem, HttpServletRequest request) {
        if (problem.status.value() == 403) {
            recordDenied(request);
        }
        return render(problem);
    }

    private static ResponseEntity<ErrorBody> render(ApiProblem problem) {
        var response = ResponseEntity.status(problem.status);
        if (problem.retryAfter != null) {
            response.header(HttpHeaders.RETRY_AFTER, problem.retryAfter.toString());
        }
        return response.body(new ErrorBody(problem.code, problem.getMessage(), null));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorBody> invalidBody(MethodArgumentNotValidException problem) {
        var violations = problem.getBindingResult().getFieldErrors().stream()
                .map(error -> new Violation(error.getField(), error.getDefaultMessage()))
                .distinct()
                .toList();
        return ResponseEntity.badRequest()
                .body(new ErrorBody("VALIDATION_ERROR", "One or more request fields are invalid.", violations));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            HandlerMethodValidationException.class})
    ResponseEntity<ErrorBody> malformed() {
        return render(ApiProblem.badRequest("The request contains invalid values or syntax."));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ErrorBody> denied(HttpServletRequest request) {
        recordDenied(request);
        return render(ApiProblem.forbidden());
    }

    private void recordDenied(HttpServletRequest request) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken token) {
            try {
                audit.record(UUID.fromString(token.getToken().getSubject()), "ACCESS_DENIED", "maintenance-api",
                        false, request.getRemoteAddr());
            } catch (RuntimeException failure) {
                LOG.warn("Could not record a denied Maintenance REST operation ({})", failure.getClass().getSimpleName());
            }
        }
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ErrorBody> missing() {
        return render(ApiProblem.notFound("The requested resource does not exist."));
    }

    @ExceptionHandler({TransientDataAccessException.class, CannotGetJdbcConnectionException.class,
            CannotCreateTransactionException.class})
    ResponseEntity<ErrorBody> unavailable() {
        return ResponseEntity.status(503).body(new ErrorBody("SERVICE_UNAVAILABLE", "Storage is temporarily unavailable.", null));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorBody> unexpected(Exception error) {
        LOG.error("Unexpected Maintenance REST failure ({})", error.getClass().getSimpleName());
        return ResponseEntity.internalServerError().body(new ErrorBody("INTERNAL_ERROR", "An unexpected error occurred.", null));
    }
}
