/**
 * Offline command adapter of the retained first-operator bootstrap (ADR-0022).
 * Exposed only so the application entry point can select command mode; it has
 * no web mapping and performs nothing unless explicitly launched.
 */
@org.springframework.modulith.NamedInterface("command")
package io.github.piresrenan.orderhub.bootstrap.adapter.in.command;
