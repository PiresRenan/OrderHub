package io.github.piresrenan.orderhub.orders.adapter.in.web;

/**
 * Signals that a structurally valid order request exceeds an explicit
 * technical resource-safety limit of the HTTP adapter.
 *
 * <p>This exception represents an operational protection boundary and must
 * not be interpreted as a business rule defining the commercial maximum
 * number of order items.</p>
 */
final class OrderRequestTooLargeException extends RuntimeException {

    private static final long serialVersionUID = 1L;
}