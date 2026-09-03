package io.github.piresrenan.orderhub.authorization.domain.model;

import org.springframework.modulith.NamedInterface;

/**
 * Capacity in which one authenticated internal User is attempting to act.
 *
 * <p>
 * Persona is not a role and grants no permission by itself.
 * </p>
 */
@NamedInterface("policy-model")
public enum AuthorizationPersona {

    STAFF,
    CUSTOMER
}
