package io.github.piresrenan.orderhub.authorization.domain.model;

import org.springframework.modulith.NamedInterface;

/**
 * Framework-neutral result of an authorization policy evaluation.
 */
@NamedInterface("customer-owned-resource")
public enum AuthorizationDecision {

    ALLOW,
    DENY
}
