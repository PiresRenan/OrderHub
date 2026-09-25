package io.github.piresrenan.orderhub.bootstrap.application.port.in;

/** Low-cardinality, identity-free result classes of one bootstrap attempt. */
public enum FirstOperatorBootstrapOutcome {
    /** This attempt performed the one privileged transition. */
    COMPLETED,
    /** The same operation and identity already completed; nothing was mutated again. */
    ALREADY_COMPLETED_SAME_OPERATION,
    /** The ceremony is closed by another operation or identity; nothing was mutated. */
    ALREADY_COMPLETED,
    /** The issuer is not one of OrderHub's configured trusted issuers; nothing was mutated. */
    UNTRUSTED_ISSUER,
    /** Existing Platform authority or an existing binding of the identity makes the transition unsafe. */
    INCOMPATIBLE_EXISTING_STATE
}
