package io.github.piresrenan.orderhub.inventory.adapter.in.web;

import java.net.URI;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.*;
import io.github.piresrenan.orderhub.inventory.application.port.in.InventoryAdministrationException;

/** Maps bounded owner outcomes while retaining framework authentication and negotiation statuses. */
@Order(-100)
@RestControllerAdvice(assignableTypes=InventoryAdministrationController.class)
public final class InventoryAdministrationExceptionHandler {
    @ExceptionHandler(Exception.class)
    ProblemDetail handle(Exception exception) {
        HttpStatusCode status;
        String code="inventory-request-rejected";
        if(exception instanceof InventoryAdministrationException failure) {
            status=switch(failure.reason()) {
                case ACCESS_DENIED -> HttpStatus.FORBIDDEN;
                case TARGET_UNAVAILABLE -> HttpStatus.NOT_FOUND;
                case CONFLICT,QUANTITY_CONFLICT -> HttpStatus.CONFLICT;
                case TECHNICAL -> HttpStatus.INTERNAL_SERVER_ERROR;
            };
            code="inventory-"+failure.reason().name().toLowerCase(java.util.Locale.ROOT).replace('_','-');
        } else if(exception instanceof AccessDeniedException) { status=HttpStatus.FORBIDDEN; }
        else if(exception instanceof AuthenticationException) { status=HttpStatus.UNAUTHORIZED; }
        else if(exception instanceof ErrorResponse error) { status=error.getStatusCode(); }
        else if(exception instanceof IllegalArgumentException || exception instanceof org.springframework.http.converter.HttpMessageNotReadableException
                || exception instanceof org.springframework.web.method.annotation.MethodArgumentTypeMismatchException) {
            status=HttpStatus.BAD_REQUEST;
        } else { status=HttpStatus.INTERNAL_SERVER_ERROR; code="inventory-technical-failure"; }
        var problem=ProblemDetail.forStatusAndDetail(status,"Inventory request could not be completed");
        problem.setType(URI.create("urn:orderhub:problem:"+code)); problem.setProperty("code",code);
        return problem;
    }
}
