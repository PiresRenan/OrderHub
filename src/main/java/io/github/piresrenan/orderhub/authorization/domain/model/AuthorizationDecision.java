package io.github.piresrenan.orderhub.authorization.domain.model;

import org.springframework.modulith.NamedInterface;

/**
 * Framework-neutral result of an authorization policy evaluation.
 */
@NamedInterface({
    "policy-model",
    "customer-owned-resource",
    "administration"
})
public enum AuthorizationDecision {

    ALLOW,
    DENY
}
