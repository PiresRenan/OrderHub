package io.github.piresrenan.orderhub.bootstrap.adapter.in.command;

/** Value-free rejection of unusable bootstrap configuration or receipt content. */
final class InvalidBootstrapInputException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** Creates the rejection without any receipt, path or configuration detail and without a cause chain. */
    InvalidBootstrapInputException() {
        super("Bootstrap input is invalid", null, false, false);
    }
}
