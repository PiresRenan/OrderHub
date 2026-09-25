/**
 * Owns the retained first-operator bootstrap ceremony (ADR-0022): its one-shot
 * state, its bounded evidence and its offline command adapter.
 *
 * <p>Users keeps owning User and binding, Authorization keeps owning the
 * Platform grant; this module reaches them only through the named contracts
 * below and never writes their tables. Nothing depends on this module, which
 * keeps the graph acyclic, and it exposes no HTTP surface.</p>
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {
                "users::api",
                "users::identity-provider-trust",
                "authorization::first-operator-bootstrap"
        })
package io.github.piresrenan.orderhub.bootstrap;
