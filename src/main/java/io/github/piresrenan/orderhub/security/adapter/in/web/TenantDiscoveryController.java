package io.github.piresrenan.orderhub.security.adapter.in.web;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.github.piresrenan.orderhub.security.application.model.AuthenticatedUserPrincipal;
import io.github.piresrenan.orderhub.security.application.port.in.DiscoverSelectableTenantsQuery;
import io.github.piresrenan.orderhub.security.application.port.in.DiscoverSelectableTenantsUseCase;

/**
 * Self-scoped Tenant discovery (ADR-0021). The actor is only the bearer-bound
 * internal User; no User, Tenant selector or provider claim is read from the request.
 */
@Tag(name = "Tenant discovery")
@RestController
public final class TenantDiscoveryController {
    private final DiscoverSelectableTenantsUseCase discovery;

    /** Requires the Security discovery boundary; construction performs no read. */
    public TenantDiscoveryController(DiscoverSelectableTenantsUseCase discovery) {
        this.discovery = discovery;
    }

    /** Returns one bounded scan window; the result never grants Tenant authority. */
    @Operation(operationId = "tenantsDiscoverSelectable", summary = "Discover selectable Tenants",
            description = "Requires the bearer identity to resolve to the current internal User. Returns only Tenants in which that User currently holds an ACTIVE membership and whose Tenant is ACTIVE, as id and name. No caller User, X-Tenant-Id selector or provider claim is consulted. Discovery is not authorization: every Tenant-scoped request independently re-establishes trusted Tenant context. Bounded scan pagination: each request scans at most `limit` of the caller's active memberships after the exclusive `afterId` cursor in PostgreSQL UUID order; items may be fewer than `limit` or empty while `nextAfterId` is non-null. Follow `nextAfterId` until it is null; do not infer the end from a short page. Current-state pages, not a snapshot, without a total count. Successful responses carry Cache-Control: no-store.")
    @ApiResponse(responseCode = "200", description = "One bounded discovery window",
            headers = @Header(name = "Cache-Control", description = "Always no-store", schema = @Schema(type = "string", allowableValues = "no-store")),
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = SelectableTenantPageResponse.class)))
    @ApiResponse(responseCode = "400", description = "Malformed afterId or limit outside 1-100",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "Missing, invalid or unbound bearer authentication",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "500", description = "Sanitized technical failure; no Tenant is returned",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping(value = "/tenants", produces = "application/json")
    ResponseEntity<SelectableTenantPageResponse> discover(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUserPrincipal actor,
            @Parameter(description = "Exclusive PostgreSQL UUID cursor taken from a previous nextAfterId; omitted for the first page", schema = @Schema(type = "string", format = "uuid")) @RequestParam(required = false) UUID afterId,
            @Parameter(description = "Maximum memberships scanned in this window; items may be fewer", schema = @Schema(type = "integer", types = {"integer"}, format = "int32", minimum = "1", maximum = "100", defaultValue = "50")) @RequestParam(defaultValue = "50") int limit) {
        if (limit < 1 || limit > DiscoverSelectableTenantsQuery.MAX_LIMIT) { throw new TenantDiscoveryRequestRejectedException(); }
        var page = discovery.discover(new DiscoverSelectableTenantsQuery(actor, afterId, limit));
        var items = page.items().stream().map(item -> new SelectableTenantResponse(item.id(), item.name())).toList();
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(new SelectableTenantPageResponse(items, page.nextAfterId()));
    }

    @Schema(name = "SelectableTenantPage", description = "One bounded discovery window; end of scan only when nextAfterId is null")
    public record SelectableTenantPageResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Selectable Tenants in ascending id order; may be empty while nextAfterId is non-null") List<SelectableTenantResponse> items,
            @Schema(type = "string", format = "uuid", types = {"string", "null"}, requiredMode = Schema.RequiredMode.REQUIRED, description = "Exclusive cursor for the next window, or null when the scan is complete; pagination control only, never authority") UUID nextAfterId) {
    }

    @Schema(name = "SelectableTenant", description = "Minimal Tenant selection projection; carries no authority")
    public record SelectableTenantResponse(
            @Schema(type = "string", format = "uuid", requiredMode = Schema.RequiredMode.REQUIRED, description = "Tenant identifier usable as an X-Tenant-Id selector, subject to independent revalidation") UUID id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 120, description = "Normalized Tenant name") String name) {
    }
}
