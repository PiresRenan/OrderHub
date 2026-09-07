package io.github.piresrenan.orderhub.administration.web;

import java.net.URI;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import io.github.piresrenan.orderhub.organizations.application.port.in.administration.AdministrationAccessDeniedException;
import io.github.piresrenan.orderhub.organizations.application.port.in.administration.AdministrativeConflictException;
import io.github.piresrenan.orderhub.organizations.application.port.in.administration.AdministrativeTargetNotFoundException;
import io.github.piresrenan.orderhub.organizations.application.port.in.administration.OrganizationNotFoundException;
import io.github.piresrenan.orderhub.organizations.application.port.in.administration.OrganizationUnavailableException;
import io.github.piresrenan.orderhub.organizations.application.port.in.administration.PlatformAdministrationAccessDeniedException;
import io.github.piresrenan.orderhub.tenants.application.port.in.administration.TenantAdministrationAccessDeniedException;
import io.github.piresrenan.orderhub.tenants.application.port.in.administration.TenantAdministrationNotFoundException;

@RestControllerAdvice(assignableTypes = AdministrationController.class)
public final class AdministrationExceptionHandler {

    @ExceptionHandler({
        AdministrationAccessDeniedException.class,
        PlatformAdministrationAccessDeniedException.class,
        TenantAdministrationAccessDeniedException.class
    })
    ProblemDetail denied() {
        return problem(HttpStatus.FORBIDDEN, "administration-access-denied",
                "Administration access denied");
    }

    @ExceptionHandler({
        AdministrativeTargetNotFoundException.class,
        OrganizationNotFoundException.class,
        TenantAdministrationNotFoundException.class
    })
    ProblemDetail notFound() {
        return problem(HttpStatus.NOT_FOUND, "administrative-target-not-found",
                "Administrative target not found");
    }

    @ExceptionHandler(OrganizationUnavailableException.class)
    ProblemDetail unavailable() {
        return problem(HttpStatus.NOT_FOUND, "organization-unavailable",
                "Organization unavailable");
    }

    @ExceptionHandler(AdministrativeConflictException.class)
    ProblemDetail conflict() {
        return problem(HttpStatus.CONFLICT, "administrative-state-conflict",
                "Administrative operation conflicts with current state");
    }

    @ExceptionHandler({
        IllegalArgumentException.class,
        MethodArgumentNotValidException.class,
        MethodArgumentTypeMismatchException.class,
        HttpMessageNotReadableException.class
    })
    ProblemDetail invalid() {
        return problem(HttpStatus.BAD_REQUEST, "invalid-administration-request",
                "Invalid administration request");
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail technical() {
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "administration-technical-failure",
                "Administration operation could not be completed");
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail) {
        var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        problem.setType(URI.create("urn:orderhub:problem:" + code));
        problem.setProperty("code", code);
        return problem;
    }
}
