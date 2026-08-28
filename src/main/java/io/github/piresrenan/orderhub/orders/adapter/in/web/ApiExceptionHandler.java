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

@RestControllerAdvice
public final class ApiExceptionHandler extends ResponseEntityExceptionHandler {

        private static final String PROBLEM_BASE = "urn:orderhub:problem:";

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