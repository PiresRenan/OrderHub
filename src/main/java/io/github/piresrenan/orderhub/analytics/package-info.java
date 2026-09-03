/**
 * Owns privacy-safe analytical facts derived from operational modules.
 *
 * <p>
 * Analytics is never a source of authorization truth and never replaces
 * operational state. It consumes bounded facts through explicit application
 * contracts and owns its own analytical schema, subject pseudonymity and
 * retention.
 * </p>
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {}
)
package io.github.piresrenan.orderhub.analytics;
