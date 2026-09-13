package io.github.piresrenan.orderhub.administration.web;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import io.github.piresrenan.orderhub.organizations.application.port.in.administration.AdministrativeOrganization;
import io.github.piresrenan.orderhub.organizations.application.port.in.administration.OrganizationAdministrationUseCase;
import io.github.piresrenan.orderhub.organizations.application.port.in.administration.OrganizationTenantSummary;
import io.github.piresrenan.orderhub.organizations.application.port.in.administration.PlatformOrganizationUseCase;
import io.github.piresrenan.orderhub.tenants.application.port.in.administration.AdministrativeTenant;
import io.github.piresrenan.orderhub.tenants.application.port.in.administration.PlatformTenantUseCase;
import io.github.piresrenan.orderhub.security.application.model.AuthenticatedUserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;

@RestController
public final class AdministrationController {

    private final PlatformOrganizationUseCase organizations;
    private final PlatformTenantUseCase tenants;
    private final OrganizationAdministrationUseCase administration;

    /** Adapts authenticated requests through owner control-plane application contracts. */
    public AdministrationController(
            PlatformOrganizationUseCase organizations,
            PlatformTenantUseCase tenants,
            OrganizationAdministrationUseCase administration) {
        this.organizations = organizations;
        this.tenants = tenants;
        this.administration = administration;
    }

    /**
     * Creates a new Platform-scoped Organization.
     *
     * @param principal authenticated actor, required to hold PLATFORM_ORGANIZATIONS_MANAGE
     * @param request Organization name
     * @return created Organization representation with its Location
     */
    @Operation(
            operationId = "platformCreateOrganization",
            summary = "Create an Organization",
            description = "Creates a new Organization. The actor must hold PLATFORM_ORGANIZATIONS_MANAGE.",
            tags = "Platform administration")
    @ApiResponse(
            responseCode = "201",
            description = "Organization created.",
            headers = @Header(name = "Location", description = "URI of the created Organization.", schema = @Schema(type = "string", format = "uri-reference")),
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = AdministrativeOrganization.class)))
    @PostMapping("/platform/organizations")
    ResponseEntity<AdministrativeOrganization> createOrganization(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @Valid @RequestBody NameRequest request) {
        var created = organizations.create(principal.userId(), request.name(), UUID.randomUUID());
        return ResponseEntity.created(URI.create("/platform/organizations/" + created.id()))
                .body(created);
    }

    /**
     * Lists every Platform-scoped Organization.
     *
     * @param principal authenticated actor, required to hold PLATFORM_ORGANIZATIONS_VIEW
     * @return unpaged list of Organizations
     */
    @Operation(
            operationId = "platformListOrganizations",
            summary = "List organizations",
            description = "Returns every Organization known to the Platform. The actor must hold "
                    + "PLATFORM_ORGANIZATIONS_VIEW. The result is an unpaged array.",
            tags = "Platform administration")
    @ApiResponse(
            responseCode = "200",
            description = "Organizations known to the Platform.",
            content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = AdministrativeOrganization.class))))
    @GetMapping("/platform/organizations")
    List<AdministrativeOrganization> listOrganizations(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return organizations.list(principal.userId());
    }

    /**
     * Suspends an Organization, idempotently.
     *
     * @param principal authenticated actor, required to hold PLATFORM_ORGANIZATIONS_MANAGE
     * @param organizationId Organization identifier
     * @return no content
     */
    @Operation(
            operationId = "platformSuspendOrganization",
            summary = "Suspend an Organization",
            description = "Suspends the Organization. The actor must hold PLATFORM_ORGANIZATIONS_MANAGE. "
                    + "Suspending an already-suspended Organization is a no-op that still returns success.",
            tags = "Platform administration")
    @ApiResponse(responseCode = "204", description = "Organization suspended or already suspended.", content = @Content)
    @ApiResponse(responseCode = "404", description = "Authorized lookup found no administrative target", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    @PutMapping("/platform/organizations/{organizationId}/suspension")
    ResponseEntity<Void> suspendOrganization(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @Parameter(description = "Organization identifier.") @PathVariable UUID organizationId) {
        organizations.suspend(principal.userId(), organizationId, UUID.randomUUID());
        return ResponseEntity.noContent().build();
    }

    /**
     * Recovers a suspended Organization back to active, idempotently.
     *
     * @param principal authenticated actor, required to hold PLATFORM_ORGANIZATIONS_MANAGE
     * @param organizationId Organization identifier
     * @return no content
     */
    @Operation(
            operationId = "platformRecoverOrganization",
            summary = "Recover an Organization",
            description = "Restores the Organization to active status. The actor must hold "
                    + "PLATFORM_ORGANIZATIONS_MANAGE. Recovering an already-active Organization is a no-op "
                    + "that still returns success.",
            tags = "Platform administration")
    @ApiResponse(responseCode = "204", description = "Organization recovered or already active.", content = @Content)
    @ApiResponse(responseCode = "404", description = "Authorized lookup found no administrative target", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    @DeleteMapping("/platform/organizations/{organizationId}/suspension")
    ResponseEntity<Void> recoverOrganization(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @Parameter(description = "Organization identifier.") @PathVariable UUID organizationId) {
        organizations.recover(principal.userId(), organizationId, UUID.randomUUID());
        return ResponseEntity.noContent().build();
    }

    /**
     * Creates a new Platform-scoped Tenant.
     *
     * @param principal authenticated actor, required to hold PLATFORM_TENANTS_MANAGE
     * @param request Tenant name
     * @return created Tenant representation with its Location
     */
    @Operation(
            operationId = "platformCreateTenant",
            summary = "Create a Tenant",
            description = "Creates a new Tenant, unattached to any Organization. The actor must hold "
                    + "PLATFORM_TENANTS_MANAGE.",
            tags = "Platform administration")
    @ApiResponse(
            responseCode = "201",
            description = "Tenant created.",
            headers = @Header(name = "Location", description = "URI of the created Tenant.", schema = @Schema(type = "string", format = "uri-reference")),
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = AdministrativeTenant.class)))
    @PostMapping("/platform/tenants")
    ResponseEntity<AdministrativeTenant> createTenant(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @Valid @RequestBody NameRequest request) {
        var created = tenants.create(principal.userId(), request.name(), UUID.randomUUID());
        return ResponseEntity.created(URI.create("/platform/tenants/" + created.id()))
                .body(created);
    }

    /**
     * Suspends a Tenant, idempotently.
     *
     * @param principal authenticated actor, required to hold PLATFORM_TENANTS_MANAGE
     * @param tenantId Tenant identifier
     * @return no content
     */
    @Operation(
            operationId = "platformSuspendTenant",
            summary = "Suspend a Tenant",
            description = "Suspends the Tenant. The actor must hold PLATFORM_TENANTS_MANAGE. Suspending an "
                    + "already-suspended Tenant is a no-op that still returns success.",
            tags = "Platform administration")
    @ApiResponse(responseCode = "204", description = "Tenant suspended or already suspended.", content = @Content)
    @ApiResponse(responseCode = "404", description = "Authorized lookup found no administrative target", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    @PutMapping("/platform/tenants/{tenantId}/suspension")
    ResponseEntity<Void> suspendTenant(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @Parameter(description = "Tenant identifier.") @PathVariable UUID tenantId) {
        tenants.suspend(principal.userId(), tenantId, UUID.randomUUID());
        return ResponseEntity.noContent().build();
    }

    /**
     * Recovers a suspended Tenant back to active, idempotently.
     *
     * @param principal authenticated actor, required to hold PLATFORM_TENANTS_MANAGE
     * @param tenantId Tenant identifier
     * @return no content
     */
    @Operation(
            operationId = "platformRecoverTenant",
            summary = "Recover a Tenant",
            description = "Restores the Tenant to active status. The actor must hold PLATFORM_TENANTS_MANAGE. "
                    + "Recovering an already-active Tenant is a no-op that still returns success.",
            tags = "Platform administration")
    @ApiResponse(responseCode = "204", description = "Tenant recovered or already active.", content = @Content)
    @ApiResponse(responseCode = "404", description = "Authorized lookup found no administrative target", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    @DeleteMapping("/platform/tenants/{tenantId}/suspension")
    ResponseEntity<Void> recoverTenant(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @Parameter(description = "Tenant identifier.") @PathVariable UUID tenantId) {
        tenants.recover(principal.userId(), tenantId, UUID.randomUUID());
        return ResponseEntity.noContent().build();
    }

    /**
     * Attaches an unattached Tenant to an Organization, idempotently.
     *
     * @param principal authenticated actor, required to hold PLATFORM_TENANTS_MANAGE
     * @param organizationId destination Organization identifier
     * @param tenantId Tenant identifier
     * @return no content
     */
    @Operation(
            operationId = "platformAttachTenant",
            summary = "Attach a Tenant to an Organization",
            description = "Attaches the Tenant to the Organization. The actor must hold PLATFORM_TENANTS_MANAGE. "
                    + "Re-attaching a Tenant already attached to this same Organization is a no-op that still "
                    + "returns success. Attaching a Tenant that is currently attached elsewhere is a conflict.",
            tags = "Platform administration")
    @ApiResponse(responseCode = "204", description = "Tenant attached or already attached to this Organization.", content = @Content)
    @ApiResponse(responseCode = "404", description = "Authorized lookup found no administrative target", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "Placement or expected source conflicts with current administrative state", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    @PutMapping("/platform/organizations/{organizationId}/tenants/{tenantId}")
    ResponseEntity<Void> attachTenant(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @Parameter(description = "Destination Organization identifier.") @PathVariable UUID organizationId,
            @Parameter(description = "Tenant identifier.") @PathVariable UUID tenantId) {
        administration.attachTenant(principal.userId(), organizationId, tenantId, UUID.randomUUID());
        return ResponseEntity.noContent().build();
    }

    /**
     * Moves a Tenant from its expected source Organization to a destination
     * Organization, using the expected source as an optimistic precondition.
     *
     * @param principal authenticated actor, required to hold PLATFORM_TENANTS_MANAGE
     * @param expectedSourceOrganizationId Organization the Tenant is expected to currently belong to
     * @param tenantId Tenant identifier
     * @param request destination Organization identifier
     * @return no content
     */
    @Operation(
            operationId = "platformMoveTenant",
            summary = "Move a Tenant between Organizations",
            description = "Moves the Tenant from expectedSourceOrganizationId to the requested destination "
                    + "Organization. The actor must hold PLATFORM_TENANTS_MANAGE. The path Organization is an "
                    + "optimistic precondition; a Tenant not currently attached to that exact Organization is "
                    + "a conflict.",
            tags = "Platform administration")
    @ApiResponse(responseCode = "204", description = "Tenant moved to the destination Organization.", content = @Content)
    @ApiResponse(responseCode = "404", description = "Authorized lookup found no administrative target", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "Placement or expected source conflicts with current administrative state", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    @PostMapping("/platform/organizations/{expectedSourceOrganizationId}/tenants/{tenantId}/moves")
    ResponseEntity<Void> moveTenant(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @Parameter(description = "Organization the Tenant is expected to currently belong to.") @PathVariable UUID expectedSourceOrganizationId,
            @Parameter(description = "Tenant identifier.") @PathVariable UUID tenantId,
            @Valid @RequestBody MoveTenantRequest request) {
        administration.moveTenant(principal.userId(), expectedSourceOrganizationId,
                request.destinationOrganizationId(), tenantId, UUID.randomUUID());
        return ResponseEntity.noContent().build();
    }

    /**
     * Detaches a Tenant from its expected Organization, idempotently.
     *
     * @param principal authenticated actor, required to hold PLATFORM_TENANTS_MANAGE
     * @param expectedOrganizationId Organization the Tenant is expected to currently belong to
     * @param tenantId Tenant identifier
     * @return no content
     */
    @Operation(
            operationId = "platformDetachTenant",
            summary = "Detach a Tenant from an Organization",
            description = "Detaches the Tenant from expectedOrganizationId. The actor must hold "
                    + "PLATFORM_TENANTS_MANAGE. Detaching a Tenant already unassigned is a no-op that still "
                    + "returns success. Detaching a Tenant currently attached to a different Organization is a "
                    + "conflict.",
            tags = "Platform administration")
    @ApiResponse(responseCode = "204", description = "Tenant detached or already unassigned.", content = @Content)
    @ApiResponse(responseCode = "404", description = "Authorized lookup found no administrative target", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "Placement or expected source conflicts with current administrative state", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    @DeleteMapping("/platform/organizations/{expectedOrganizationId}/tenants/{tenantId}")
    ResponseEntity<Void> detachTenant(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @Parameter(description = "Organization the Tenant is expected to currently belong to.") @PathVariable UUID expectedOrganizationId,
            @Parameter(description = "Tenant identifier.") @PathVariable UUID tenantId) {
        administration.detachTenant(principal.userId(), expectedOrganizationId, tenantId, UUID.randomUUID());
        return ResponseEntity.noContent().build();
    }

    /**
     * Grants an Organization-scoped administrative permission to a User.
     *
     * @param principal authenticated actor, required to hold PLATFORM_ORGANIZATION_GRANTS_MANAGE
     * @param organizationId Organization scope of the grant
     * @param userId User receiving the grant
     * @param permissionCode Organization-scoped permission code to grant
     * @return no content
     */
    @Operation(
            operationId = "platformGrantOrganizationPermission",
            summary = "Grant an Organization-scoped administrative permission",
            description = "Grants permissionCode to the User within the Organization scope. The actor must "
                    + "hold PLATFORM_ORGANIZATION_GRANTS_MANAGE. Only ORGANIZATION_TENANTS_VIEW is currently a "
                    + "valid Organization-scoped permission code. Repeating the same desired grant state succeeds without duplicating the grant.",
            tags = "Platform administration")
    @ApiResponse(responseCode = "204", description = "Permission granted.", content = @Content)
    @ApiResponse(responseCode = "404", description = "Authorized lookup found no administrative target", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    @PutMapping("/platform/organizations/{organizationId}/administrative-grants/{userId}/permissions/{permissionCode}")
    ResponseEntity<Void> grant(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @Parameter(description = "Organization scope of the grant.") @PathVariable UUID organizationId,
            @Parameter(description = "User receiving the grant.") @PathVariable UUID userId,
            @Parameter(description = "Organization-scoped permission code", schema = @Schema(allowableValues = {"ORGANIZATION_TENANTS_VIEW"}))
            @PathVariable String permissionCode) {
        administration.grant(principal.userId(), organizationId, userId, permissionCode, UUID.randomUUID());
        return ResponseEntity.noContent().build();
    }

    /**
     * Revokes an Organization-scoped administrative permission from a User.
     *
     * @param principal authenticated actor, required to hold PLATFORM_ORGANIZATION_GRANTS_MANAGE
     * @param organizationId Organization scope of the grant
     * @param userId User losing the grant
     * @param permissionCode Organization-scoped permission code to revoke
     * @return no content
     */
    @Operation(
            operationId = "platformRevokeOrganizationPermission",
            summary = "Revoke an Organization-scoped administrative permission",
            description = "Revokes permissionCode from the User within the Organization scope. The actor must "
                    + "hold PLATFORM_ORGANIZATION_GRANTS_MANAGE. Only ORGANIZATION_TENANTS_VIEW is currently a "
                    + "valid Organization-scoped permission code. Repeating the same desired grant state succeeds without duplicating the grant.",
            tags = "Platform administration")
    @ApiResponse(responseCode = "204", description = "Permission revoked.", content = @Content)
    @ApiResponse(responseCode = "404", description = "Authorized lookup found no administrative target", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    @DeleteMapping("/platform/organizations/{organizationId}/administrative-grants/{userId}/permissions/{permissionCode}")
    ResponseEntity<Void> revoke(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @Parameter(description = "Organization scope of the grant.") @PathVariable UUID organizationId,
            @Parameter(description = "User losing the grant.") @PathVariable UUID userId,
            @Parameter(description = "Organization-scoped permission code", schema = @Schema(allowableValues = {"ORGANIZATION_TENANTS_VIEW"}))
            @PathVariable String permissionCode) {
        administration.revoke(principal.userId(), organizationId, userId, permissionCode, UUID.randomUUID());
        return ResponseEntity.noContent().build();
    }

    /**
     * Lists every Tenant attached to an Organization.
     *
     * @param principal authenticated actor, required to hold ORGANIZATION_TENANTS_VIEW for the exact Organization
     * @param organizationId Organization identifier
     * @return unpaged list of attached Tenants
     */
    @Operation(
            operationId = "organizationListTenants",
            summary = "List an Organization's tenants",
            description = "Returns every Tenant currently attached to the Organization. The actor must hold "
                    + "ORGANIZATION_TENANTS_VIEW for this exact Organization. A denied actor, an absent "
                    + "Organization and a suspended Organization are all reported identically as not found to "
                    + "avoid revealing Organization existence or status to an unauthorized caller. The result "
                    + "is an unpaged array.",
            tags = "Organization administration")
    @ApiResponse(
            responseCode = "200",
            description = "Tenants attached to the Organization.",
            content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = OrganizationTenantSummary.class))))
    @ApiResponse(responseCode = "404", description = "Organization is absent, suspended or unavailable to this actor", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    @GetMapping("/organizations/{organizationId}/tenants")
    List<OrganizationTenantSummary> listOrganizationTenants(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @Parameter(description = "Organization identifier.") @PathVariable UUID organizationId) {
        return administration.listTenants(principal.userId(), organizationId);
    }

    @Schema(name = "AdministrativeNameRequest")
    public record NameRequest(
            @NormalizedAdministrativeName
            @Schema(
                    description = "Organization or Tenant name: nonblank after surrounding Unicode whitespace is stripped, at most 120 code points after normalization",
                    minLength = 1,
                    pattern = "^[\\u0009-\\u000D\\u001C-\\u0020\\u1680\\u2000-\\u2006\\u2008-\\u200A\\u2028\\u2029\\u205F\\u3000]*[^\\u0009-\\u000D\\u001C-\\u0020\\u1680\\u2000-\\u2006\\u2008-\\u200A\\u2028\\u2029\\u205F\\u3000](?:[\\s\\S]{0,118}[^\\u0009-\\u000D\\u001C-\\u0020\\u1680\\u2000-\\u2006\\u2008-\\u200A\\u2028\\u2029\\u205F\\u3000])?[\\u0009-\\u000D\\u001C-\\u0020\\u1680\\u2000-\\u2006\\u2008-\\u200A\\u2028\\u2029\\u205F\\u3000]*$",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String name) {
    }

    @Schema(name = "AdministrativeMoveTenantRequest")
    public record MoveTenantRequest(
            @NotNull
            @Schema(
                    description = "Destination Organization identifier.",
                    format = "uuid",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            UUID destinationOrganizationId) {
    }
}
