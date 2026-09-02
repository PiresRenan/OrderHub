package io.github.piresrenan.orderhub.orders.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;

import io.github.piresrenan.orderhub.orders.application.port.out.CreateOrderIdempotencyInProgressException;
import io.github.piresrenan.orderhub.orders.application.port.out.CreateOrderIdempotencyPersistenceException;

class OrderIdempotencyRecoveryHttpContractTest {

    private static final String REUSED_EXCEPTION =
            "io.github.piresrenan.orderhub.orders.application.idempotency."
                    + "CreateOrderIdempotencyKeyReusedException";

    @Test
    void mapsIdempotencyKeyReuseToPrivacySafe422ProblemDetail() {

        var reusedType =
                requireType(
                        REUSED_EXCEPTION);

        var response =
                invokeNoArgumentHandler(
                        "handleCreateOrderIdempotencyKeyReused",
                        reusedType);

        assertProblem(
                response,
                HttpStatus.UNPROCESSABLE_ENTITY,
                "urn:orderhub:problem:idempotency-key-reused",
                "IDEMPOTENCY_KEY_REUSED",
                "The request idempotency key cannot be reused for different request content.");
    }

    @Test
    void mapsBoundedIdempotencyAcquisitionContentionToPrivacySafe409ProblemDetail() {

        var response =
                invokeNoArgumentHandler(
                        "handleCreateOrderIdempotencyInProgress",
                        CreateOrderIdempotencyInProgressException.class);

        assertProblem(
                response,
                HttpStatus.CONFLICT,
                "urn:orderhub:problem:idempotency-request-in-progress",
                "IDEMPOTENCY_REQUEST_IN_PROGRESS",
                "A request with this idempotency key is still being processed.");
    }

    @Test
    void classifiesIdempotencyPersistenceFailureAsGenericInternalError() {

        try {

            var method =
                    ApiExceptionHandler.class.getDeclaredMethod(
                            "handleInternalTechnicalFailure");

            var annotation =
                    method.getAnnotation(
                            ExceptionHandler.class);

            assertThat(annotation)
                    .as(
                            "Internal technical handler must declare @ExceptionHandler")
                    .isNotNull();

            assertThat(
                    Arrays.stream(
                                    annotation.value())
                            .map(Class::getName)
                            .toList())
                    .contains(
                            CreateOrderIdempotencyPersistenceException.class
                                    .getName());

            method.setAccessible(
                    true);

            @SuppressWarnings("unchecked")
            var response =
                    (ResponseEntity<Object>) method.invoke(
                            new ApiExceptionHandler());

            assertProblem(
                    response,
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "urn:orderhub:problem:internal-error",
                    "INTERNAL_ERROR",
                    "The request could not be completed.");

        } catch (NoSuchMethodException exception) {

            throw new AssertionError(
                    exception);

        } catch (InvocationTargetException exception) {

            throw new AssertionError(
                    exception.getCause());

        } catch (ReflectiveOperationException exception) {

            throw new AssertionError(
                    exception);
        }
    }

    private static ResponseEntity<Object> invokeNoArgumentHandler(
            String methodName,
            Class<?> handledException) {

        try {

            var method =
                    ApiExceptionHandler.class.getDeclaredMethod(
                            methodName);

            var annotation =
                    method.getAnnotation(
                            ExceptionHandler.class);

            assertThat(annotation)
                    .as(
                            methodName + " must declare @ExceptionHandler")
                    .isNotNull();

            assertThat(
                    Arrays.stream(
                                    annotation.value())
                            .map(Class::getName)
                            .toList())
                    .contains(
                            handledException.getName());

            method.setAccessible(
                    true);

            @SuppressWarnings("unchecked")
            var response =
                    (ResponseEntity<Object>) method.invoke(
                            new ApiExceptionHandler());

            return response;

        } catch (NoSuchMethodException exception) {

            throw new AssertionError(
                    "Expected idempotency HTTP handler is absent: "
                            + methodName,
                    exception);

        } catch (InvocationTargetException exception) {

            throw new AssertionError(
                    exception.getCause());

        } catch (ReflectiveOperationException exception) {

            throw new AssertionError(
                    exception);
        }
    }

    private static void assertProblem(
            ResponseEntity<Object> response,
            HttpStatus expectedStatus,
            String expectedType,
            String expectedCode,
            String expectedDetail) {

        assertThat(response.getStatusCode())
                .isEqualTo(
                        expectedStatus);

        assertThat(response.getBody())
                .isInstanceOf(
                        ProblemDetail.class);

        var problem =
                (ProblemDetail) response.getBody();

        assertThat(problem.getType())
                .hasToString(
                        expectedType);

        assertThat(problem.getStatus())
                .isEqualTo(
                        expectedStatus.value());

        assertThat(problem.getDetail())
                .isEqualTo(
                        expectedDetail);

        assertThat(problem.getProperties())
                .containsEntry(
                        "code",
                        expectedCode);

        var serializedContract =
                String.valueOf(
                        problem);

        assertThat(serializedContract)
                .doesNotContain(
                        "key_digest",
                        "request_fingerprint",
                        "tenantId",
                        "orderId",
                        "customerId",
                        "variantId");
    }

    private static Class<?> requireType(
            String name) {

        try {
            return Class.forName(
                    name);
        } catch (ClassNotFoundException exception) {
            throw new AssertionError(
                    "Expected application idempotency exception is absent: "
                            + name,
                    exception);
        }
    }
}
