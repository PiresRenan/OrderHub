package io.github.piresrenan.orderhub.customers.application.port.in;

import org.springframework.modulith.NamedInterface;

/**
 * Result of resolving one exact Customer account relationship.
 */
@NamedInterface("account-binding")
public enum CustomerAccountBindingResolution {

    BOUND,
    NOT_BOUND
}
