package io.github.piresrenan.orderhub.administration.web;

import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import io.github.piresrenan.orderhub.users.application.port.in.ExternalIdentityLifecycleUnavailableException;
import io.github.piresrenan.orderhub.workforce.application.service.StaffProvisioningUnavailableException;

/** Never copies exceptions, selectors, credentials or provider claims into HTTP failures. */
@org.springframework.core.annotation.Order(0)
@RestControllerAdvice(assignableTypes = {IdentityBootstrapController.class, IdentityLifecycleController.class})
public final class IdentityLifecycleExceptionHandler {
    @ExceptionHandler({ExternalIdentityLifecycleUnavailableException.class, StaffProvisioningUnavailableException.class,
            io.github.piresrenan.orderhub.customers.application.port.in.linking.CustomerLinkUnavailableException.class,
            io.github.piresrenan.orderhub.workforce.application.port.in.TenantMembershipAdministrationUnavailableException.class,
            io.github.piresrenan.orderhub.authorization.application.port.in.provisioning.StaffProvisioningAuthorizationDeniedException.class})
    /** Keeps unavailable proof, ownership and authority outcomes equivalent without private diagnostics. */
    ProblemDetail denied() { return problem(HttpStatus.FORBIDDEN, "identity-lifecycle-unavailable", "Identity lifecycle operation is unavailable"); }
    /** Reports authorized state conflict separately from uncertain persistence or transaction failure. */
    @ExceptionHandler(io.github.piresrenan.orderhub.workforce.application.port.in.StaffProvisioningConflictException.class)
    ProblemDetail conflict() { return problem(HttpStatus.CONFLICT, "identity-lifecycle-conflict", "Identity lifecycle operation conflicts with current state"); }
    @ExceptionHandler({IllegalArgumentException.class,
            org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
            org.springframework.web.bind.MethodArgumentNotValidException.class})
    /** Preserves malformed request semantics without reflecting rejected values. */
    ProblemDetail invalid() { return problem(HttpStatus.BAD_REQUEST, "invalid-identity-lifecycle-request", "Invalid identity lifecycle request"); }
    /** Preserves framework statuses and maps infrastructure uncertainty to sanitized technical failure. */
    @ExceptionHandler(Exception.class)
    ProblemDetail technical(Exception exception) {
        if (exception instanceof ErrorResponse response) { return problem(response.getStatusCode(), "identity-lifecycle-request-rejected", "Identity lifecycle request could not be processed"); }
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "identity-lifecycle-technical-failure", "Identity lifecycle operation could not be completed");
    }
    /** Builds bounded error fields and a fixed instance path without reflecting private selectors. */
    private static ProblemDetail problem(HttpStatusCode status, String code, String detail) {
        var result = ProblemDetail.forStatusAndDetail(status, detail);
        result.setType(URI.create("urn:orderhub:problem:" + code)); result.setProperty("code", code);
        result.setInstance(URI.create("/identity-lifecycle"));
        return result;
    }
}
