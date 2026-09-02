package io.github.piresrenan.orderhub.authorization.domain.model;

/**
 * Protection class controlling how one role definition may evolve.
 */
public enum RoleMutability {

    SYSTEM_LOCKED,
    TENANT_PROTECTED,
    BUILTIN_FUNCTIONAL,
    TENANT_CUSTOM
}
