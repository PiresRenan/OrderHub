package io.github.piresrenan.orderhub.catalog.adapter.in.web;

import java.net.URI;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.*;
import io.github.piresrenan.orderhub.catalog.application.port.in.CategoryHierarchyViolationException;
import io.github.piresrenan.orderhub.catalog.application.port.in.administration.*;

/** Fixed public outcomes; neither framework nor persistence messages enter Problem Details. */
@Order(-100)
@RestControllerAdvice(assignableTypes=CatalogAdministrationController.class)
public final class CatalogAdministrationExceptionHandler {
    @ExceptionHandler(Exception.class)
    ProblemDetail handle(Exception exception) {
        HttpStatusCode status;
        String code;
        if(exception instanceof CatalogAdminDeniedException || exception instanceof AccessDeniedException) {
            status=HttpStatus.FORBIDDEN; code="catalog-access-denied";
        } else if(exception instanceof AuthenticationException) {
            status=HttpStatus.UNAUTHORIZED; code="catalog-authentication-required";
        } else if(exception instanceof CatalogAdminNotFoundException) {
            status=HttpStatus.NOT_FOUND; code="catalog-target-not-found";
        } else if(exception instanceof CatalogAdminConflictException || exception instanceof CategoryHierarchyViolationException) {
            status=HttpStatus.CONFLICT; code="catalog-state-conflict";
        } else if(exception instanceof ErrorResponse error) {
            status=error.getStatusCode(); code="catalog-request-rejected";
        } else if(exception instanceof IllegalArgumentException || exception instanceof org.springframework.http.converter.HttpMessageNotReadableException
                || exception instanceof org.springframework.web.method.annotation.MethodArgumentTypeMismatchException) {
            status=HttpStatus.BAD_REQUEST; code="invalid-catalog-request";
        } else {
            status=HttpStatus.INTERNAL_SERVER_ERROR; code="catalog-technical-failure";
        }
        var problem=ProblemDetail.forStatusAndDetail(status,"Catalog request could not be completed");
        problem.setType(URI.create("urn:orderhub:problem:"+code)); problem.setProperty("code",code);
        return problem;
    }
}
