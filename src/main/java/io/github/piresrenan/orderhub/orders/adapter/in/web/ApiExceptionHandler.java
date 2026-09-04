package io.github.piresrenan.orderhub.orders.adapter.in.web;

import java.net.URI;
import java.util.Comparator;

import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import io.github.piresrenan.orderhub.catalog.application.port.in.orderability.CatalogOrderabilityRejectedException;
import io.github.piresrenan.orderhub.catalog.application.port.in.orderability.CatalogOrderabilityTechnicalException;
import io.github.piresrenan.orderhub.customers.application.port.in.CustomerAccountBindingTechnicalException;
import io.github.piresrenan.orderhub.inventory.application.port.in.InventoryCommitmentRejectedException;
import io.github.piresrenan.orderhub.inventory.application.port.in.InventoryOperationException;
import io.github.piresrenan.orderhub.orders.application.idempotency.CreateOrderIdempotencyKeyReusedException;
import io.github.piresrenan.orderhub.orders.application.port.in.CustomerOrderAccessDeniedException;
import io.github.piresrenan.orderhub.orders.application.port.in.CustomerOrderUnavailableException;
import io.github.piresrenan.orderhub.orders.application.port.out.CreateOrderIdempotencyInProgressException;
import io.github.piresrenan.orderhub.orders.application.port.out.CreateOrderIdempotencyPersistenceException;
import io.github.piresrenan.orderhub.orders.application.port.out.OrderPersistenceException;
import io.github.piresrenan.orderhub.orders.application.port.out.TransactionExecutionException;

@RestControllerAdvice
public final class ApiExceptionHandler extends ResponseEntityExceptionHandler {

        private static final String PROBLEM_BASE = "urn:orderhub:problem:";

        /**
         * Converts a missing or syntactically invalid create-Order idempotency key
         * into a stable RFC 9457 response without reflecting the supplied header.
         *
         * @return privacy-safe 400 response for an invalid Idempotency-Key contract
         */
        @ExceptionHandler(OrderIdempotencyKeyInvalidException.class)
        protected ResponseEntity<Object> handleOrderIdempotencyKeyInvalid() {

                var status =
                                HttpStatus.BAD_REQUEST;

                var problem =
                                problem(
                                                status,
                                                "idempotency-key-invalid",
                                                "Invalid idempotency key",
                                                "The request idempotency key is missing or invalid.",
                                                "IDEMPOTENCY_KEY_INVALID");

                return ResponseEntity
                                .status(status)
                                .body(problem);
        }

        /**
         * Rejects reuse of one durable idempotency identity for different
         * canonical create-Order request content.
         */
        @ExceptionHandler(CreateOrderIdempotencyKeyReusedException.class)
        protected ResponseEntity<Object> handleCreateOrderIdempotencyKeyReused() {

                var status =
                                HttpStatus.UNPROCESSABLE_ENTITY;

                var problem =
                                problem(
                                                status,
                                                "idempotency-key-reused",
                                                "Idempotency key reused",
                                                "The request idempotency key cannot be reused for different request content.",
                                                "IDEMPOTENCY_KEY_REUSED");

                return ResponseEntity
                                .status(status)
                                .body(problem);
        }

        /**
         * Reports that bounded durable idempotency acquisition could not finish
         * while another transaction still owned the conflicting identity.
         */
        @ExceptionHandler(CreateOrderIdempotencyInProgressException.class)
        protected ResponseEntity<Object> handleCreateOrderIdempotencyInProgress() {

                var status =
                                HttpStatus.CONFLICT;

                var problem =
                                problem(
                                                status,
                                                "idempotency-request-in-progress",
                                                "Idempotent request in progress",
                                                "A request with this idempotency key is still being processed.",
                                                "IDEMPOTENCY_REQUEST_IN_PROGRESS");

                return ResponseEntity
                                .status(status)
                                .body(problem);
        }

        /**
         * Converts Customer self-service authorization denial into a stable
         * privacy-safe response without exposing Customer, Tenant, User, binding
         * or relationship evidence.
         */
        @ExceptionHandler(CustomerOrderAccessDeniedException.class)
        protected ResponseEntity<Object> handleCustomerOrderAccessDenied() {

                var status = HttpStatus.FORBIDDEN;

                var problem = problem(
                                status,
                                "customer-order-access-denied",
                                "Customer order access denied",
                                "The requested customer order operation is not permitted.",
                                "CUSTOMER_ORDER_ACCESS_DENIED");

                return ResponseEntity
                                .status(status)
                                .body(problem);
        }
        /**
         * Converts an unavailable Customer Order read into a privacy-safe
         * not-found response without revealing whether the Order is absent or
         * inaccessible to the authenticated Customer.
         */
        @ExceptionHandler(CustomerOrderUnavailableException.class)
        protected ResponseEntity<Object> handleCustomerOrderUnavailable() {

                var status = HttpStatus.NOT_FOUND;

                var problem = problem(
                                status,
                                "order-not-found",
                                "Order not found",
                                "The requested order could not be found.",
                                "ORDER_NOT_FOUND");

                return ResponseEntity
                                .status(status)
                                .body(problem);
        }

        /**
         * Converts an Orders-specific technical resource-limit violation into a stable
         * RFC 9457 response without exposing request contents or configured thresholds.
         *
         * @return privacy-safe 413 response for a structurally valid request that
         *         exceeds the adapter's technical processing limit
         */
        @ExceptionHandler(OrderRequestTooLargeException.class)
        protected ResponseEntity<Object> handleOrderRequestTooLarge() {

                var status = HttpStatus.PAYLOAD_TOO_LARGE;

                var problem = problem(
                                status,
                                "request-too-large",
                                "Request too large",
                                "The request exceeds the supported processing limits.",
                                "REQUEST_TOO_LARGE");

                return ResponseEntity
                                .status(status)
                                .body(problem);
        }

        /**
         * Converts an application persistence failure into a stable internal-error
         * contract without exposing storage technology, identifiers or exception
         * internals.
         *
         * @return privacy-safe RFC 9457 response for a persistence operation that
         *         could not be completed
         */
        /**
         * Converts fail-closed Inventory commitment rejection into a stable
         * conflict response without exposing Tenant, Variant, policy, stock or
         * position state.
         */
        /**
         * Converts Catalog commercial-orderability rejection into a generic
         * conflict response without exposing whether a Product/Variant exists,
         * belongs to another Tenant or has a non-ACTIVE lifecycle state.
         */
        @ExceptionHandler(CatalogOrderabilityRejectedException.class)
        protected ResponseEntity<Object> handleCatalogOrderabilityRejected() {

                var status = HttpStatus.CONFLICT;

                var problem = problem(
                                status,
                                "order-not-accepted",
                                "Order could not be accepted",
                                "The order could not be accepted.",
                                "ORDER_NOT_ACCEPTED");

                return ResponseEntity
                                .status(status)
                                .body(problem);
        }
        @ExceptionHandler(InventoryCommitmentRejectedException.class)
        protected ResponseEntity<Object> handleInventoryCommitmentRejected() {

                var status = HttpStatus.CONFLICT;

                var problem = problem(
                                status,
                                "inventory-commitment-rejected",
                                "Inventory commitment rejected",
                                "The order could not be accepted.",
                                "INVENTORY_COMMITMENT_REJECTED");

                return ResponseEntity
                                .status(status)
                                .body(problem);
        }
        @ExceptionHandler({
                        CreateOrderIdempotencyPersistenceException.class,
                        OrderPersistenceException.class,
                        TransactionExecutionException.class,
                        InventoryOperationException.class,
                        CatalogOrderabilityTechnicalException.class,
                        CustomerAccountBindingTechnicalException.class
        })
        protected ResponseEntity<Object> handleInternalTechnicalFailure() {

                var status = HttpStatus.INTERNAL_SERVER_ERROR;

                var problem = problem(
                                status,
                                "internal-error",
                                "Internal server error",
                                "The request could not be completed.",
                                "INTERNAL_ERROR");

                return ResponseEntity
                                .status(status)
                                .body(problem);
        }

        /**
         * Converts Bean Validation failures into a stable RFC 9457 contract.
         *
         * <p>
         * Rejected values are deliberately excluded to prevent accidental
         * disclosure of personal or sensitive information.
         * </p>
         */
        @Override
        protected ResponseEntity<Object> handleMethodArgumentNotValid(
                        MethodArgumentNotValidException exception,
                        HttpHeaders headers,
                        HttpStatusCode status,
                        WebRequest request) {

                var errors = exception.getBindingResult()
                                .getFieldErrors()
                                .stream()
                                .sorted(Comparator.comparing(error -> error.getField()))
                                .map(error -> new ValidationError(
                                                error.getField(),
                                                error.getCode(),
                                                error.getDefaultMessage()))
                                .toList();

                var problem = problem(
                                status,
                                "validation",
                                "Request validation failed",
                                "One or more request fields are invalid.",
                                "REQUEST_VALIDATION_FAILED");

                problem.setProperty("errors", errors);

                return handleExceptionInternal(
                                exception,
                                problem,
                                headers,
                                status,
                                request);
        }

        /**
         * Handles malformed, incomplete or non-deserializable JSON without exposing
         * parser internals or the original request body.
         */
        @Override
        protected ResponseEntity<Object> handleHttpMessageNotReadable(
                        HttpMessageNotReadableException exception,
                        HttpHeaders headers,
                        HttpStatusCode status,
                        WebRequest request) {

                var problem = problem(
                                status,
                                "malformed-request",
                                "Malformed request body",
                                "Request body could not be parsed as valid input.",
                                "MALFORMED_REQUEST");

                return handleExceptionInternal(
                                exception,
                                problem,
                                headers,
                                status,
                                request);
        }

        /**
         * Handles request values that cannot be converted to the expected Java type,
         * such as an invalid UUID header.
         */
        @Override
        protected ResponseEntity<Object> handleTypeMismatch(
                        TypeMismatchException exception,
                        HttpHeaders headers,
                        HttpStatusCode status,
                        WebRequest request) {

                var problem = problem(
                                status,
                                "type-mismatch",
                                "Invalid request value",
                                "A request value has an invalid type or format.",
                                "TYPE_MISMATCH");

                return handleExceptionInternal(
                                exception,
                                problem,
                                headers,
                                status,
                                request);
        }

        /**
         * Handles missing mandatory request metadata such as required headers.
         */
        @Override
        protected ResponseEntity<Object> handleServletRequestBindingException(
                        ServletRequestBindingException exception,
                        HttpHeaders headers,
                        HttpStatusCode status,
                        WebRequest request) {

                var problem = problem(
                                status,
                                "request-binding",
                                "Invalid request metadata",
                                "A required request value is missing or invalid.",
                                "REQUEST_BINDING_FAILED");

                return handleExceptionInternal(
                                exception,
                                problem,
                                headers,
                                status,
                                request);
        }

        /**
         * Handles callers that send a representation unsupported by the endpoint.
         */
        @Override
        protected ResponseEntity<Object> handleHttpMediaTypeNotSupported(
                        HttpMediaTypeNotSupportedException exception,
                        HttpHeaders headers,
                        HttpStatusCode status,
                        WebRequest request) {

                var problem = problem(
                                status,
                                "unsupported-media-type",
                                "Unsupported media type",
                                "This endpoint requires a supported request representation.",
                                "UNSUPPORTED_MEDIA_TYPE");

                return handleExceptionInternal(
                                exception,
                                problem,
                                headers,
                                status,
                                request);
        }

        /**
         * Converts failed response-content negotiation into the same stable RFC 9457
         * contract used by the rest of the OrderHub HTTP boundary.
         *
         * <p>
         * The response deliberately avoids reproducing the caller's Accept header
         * or framework-specific negotiation details.
         * </p>
         */
        @Override
        protected ResponseEntity<Object> handleHttpMediaTypeNotAcceptable(
                        HttpMediaTypeNotAcceptableException exception,
                        HttpHeaders headers,
                        HttpStatusCode status,
                        WebRequest request) {

                var problem = problem(
                                status,
                                "not-acceptable",
                                "Not acceptable",
                                "The requested response representation is not supported.",
                                "NOT_ACCEPTABLE");

                return handleExceptionInternal(
                                exception,
                                problem,
                                headers,
                                status,
                                request);
        }

        /**
         * Handles attempts to invoke the resource with an unsupported HTTP method.
         */
        @Override
        protected ResponseEntity<Object> handleHttpRequestMethodNotSupported(
                        HttpRequestMethodNotSupportedException exception,
                        HttpHeaders headers,
                        HttpStatusCode status,
                        WebRequest request) {

                var problem = problem(
                                status,
                                "method-not-allowed",
                                "Method not allowed",
                                "The requested HTTP method is not supported by this resource.",
                                "METHOD_NOT_ALLOWED");

                return handleExceptionInternal(
                                exception,
                                problem,
                                headers,
                                status,
                                request);
        }

        /**
         * Builds the common machine-readable RFC 9457 representation used by the
         * OrderHub API.
         *
         * @return a privacy-safe problem response without rejected values or
         *         internal implementation details
         */
        private ProblemDetail problem(
                        HttpStatusCode status,
                        String type,
                        String title,
                        String detail,
                        String code) {

                var problem = ProblemDetail.forStatus(status);

                problem.setType(URI.create(PROBLEM_BASE + type));
                problem.setTitle(title);
                problem.setDetail(detail);
                problem.setProperty("code", code);

                return problem;
        }

        private record ValidationError(
                        String field,
                        String code,
                        String message) {
        }
}
