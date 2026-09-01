package io.github.piresrenan.orderhub.catalog.application.port.in;

/**
 * Signals that a requested Category hierarchy mutation cannot be accepted.
 *
 * <p>
 * The message deliberately does not reveal which tenant-scoped Category was
 * missing or which ancestry node caused the rejection.
 * </p>
 */
public final class CategoryHierarchyViolationException
        extends RuntimeException {

    private static final String MESSAGE =
            "Category hierarchy is invalid.";

    public CategoryHierarchyViolationException() {
        super(MESSAGE);
    }
}