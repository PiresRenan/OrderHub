package io.github.piresrenan.orderhub.users.application.port.in;

/** Rejects exclusive establishment because the exact pair already identifies a User; carries no identity detail. */
public final class ExternalIdentityAlreadyBoundException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** Creates the bounded rejection without issuer, subject or internal User identifiers. */
    public ExternalIdentityAlreadyBoundException() { super("External identity is already bound"); }
}
