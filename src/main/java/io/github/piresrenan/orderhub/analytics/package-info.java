/**
 * Owns privacy-safe analytical facts derived from operational modules.
 *
 * <p>
 * Analytics is never a source of authorization truth and never replaces
 * operational state. It consumes bounded facts through explicit application
 * contracts and owns its own analytical schema, subject pseudonymity and
 * retention.
 * </p>
 *
 * <p>
 * The single permitted dependency is the reviewed workforce named interface
 * that carries the authority-change notification and its bounded source
 * contract. Naming the interface rather than the module keeps workforce
 * persistence, configuration and internal services unreachable, so the edge
 * cannot widen without another explicit change here. Workforce does not depend
 * on analytics, which is what keeps the graph acyclic.
 * </p>
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = "workforce :: authority-change-analytics-source"
)
package io.github.piresrenan.orderhub.analytics;
