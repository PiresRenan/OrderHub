package io.github.piresrenan.orderhub.authorization.domain.model;

import static io.github.piresrenan.orderhub.authorization.domain.model.PermissionCode.CUSTOMER_ORDERS_CREATE;
import static io.github.piresrenan.orderhub.authorization.domain.model.PermissionCode.CUSTOMER_ORDERS_VIEW;
import static io.github.piresrenan.orderhub.authorization.domain.model.PermissionCode.ORDERS_CREATE;
import static io.github.piresrenan.orderhub.authorization.domain.model.PermissionCode.ORDERS_VIEW;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CustomerOrderPermissionCompatibilityTest {

    @Test
    void customerOrderPermissionsAreCompatibleOnlyWithCustomerPersona() {

        assertThat(
                CUSTOMER_ORDERS_VIEW.supports(
                        AuthorizationPersona.CUSTOMER))
                .isTrue();

        assertThat(
                CUSTOMER_ORDERS_CREATE.supports(
                        AuthorizationPersona.CUSTOMER))
                .isTrue();

        assertThat(
                CUSTOMER_ORDERS_VIEW.supports(
                        AuthorizationPersona.STAFF))
                .isFalse();

        assertThat(
                CUSTOMER_ORDERS_CREATE.supports(
                        AuthorizationPersona.STAFF))
                .isFalse();
    }

    @Test
    void existingStaffOrderPermissionsRemainStaffOnly() {

        assertThat(
                ORDERS_VIEW.supports(
                        AuthorizationPersona.STAFF))
                .isTrue();

        assertThat(
                ORDERS_CREATE.supports(
                        AuthorizationPersona.STAFF))
                .isTrue();

        assertThat(
                ORDERS_VIEW.supports(
                        AuthorizationPersona.CUSTOMER))
                .isFalse();

        assertThat(
                ORDERS_CREATE.supports(
                        AuthorizationPersona.CUSTOMER))
                .isFalse();
    }
}
