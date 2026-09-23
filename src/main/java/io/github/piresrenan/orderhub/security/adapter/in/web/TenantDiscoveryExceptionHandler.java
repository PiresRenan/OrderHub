package io.github.piresrenan.orderhub.security.adapter.in.web;

import java.net.URI;

import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Never copies exceptions, cursors, identities or provider claims into HTTP failures.
 * Filtering never reaches this handler; only malformed input or technical failure does.
 */
@Order(0)
@RestControllerAdvice(assignableTypes = TenantDiscoveryController.class)
public final class TenantDiscoveryExceptionHandler {

    /** Malformed cursor or out-of-range limit, without reflecting the rejected value. */
    @ExceptionHandler({TenantDiscoveryRequestRejectedException.class, MethodArgumentTypeMismatchException.class})
    ProblemDetail invalid() {
        return problem(HttpStatus.BAD_REQUEST, "invalid-tenant-discovery-request", "Invalid Tenant discovery request");
    }

    /** Preserves framework statuses and maps persistence or state uncertainty to a sanitized technical failure. */
    @ExceptionHandler(Exception.class)
    ProblemDetail technical(Exception exception) {
        if (exception instanceof ErrorResponse response) {
            return problem(response.getStatusCode(), "tenant-discovery-request-rejected", "Tenant discovery request could not be processed");
        }
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "tenant-discovery-technical-failure", "Tenant discovery could not be completed");
    }

    /** Builds bounded fields and a fixed instance path. */
    private static ProblemDetail problem(HttpStatusCode status, String code, String detail) {
        var result = ProblemDetail.forStatusAndDetail(status, detail);
        result.setType(URI.create("urn:orderhub:problem:" + code));
        result.setProperty("code", code);
        result.setInstance(URI.create("/tenants"));
        return result;
    }
}
