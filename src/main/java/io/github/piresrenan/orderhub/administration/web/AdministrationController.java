package io.github.piresrenan.orderhub.administration.web;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

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

@RestController
public final class AdministrationController {

    private final PlatformOrganizationUseCase organizations;
    private final PlatformTenantUseCase tenants;
    private final OrganizationAdministrationUseCase administration;

    public AdministrationController(
            PlatformOrganizationUseCase organizations,
            PlatformTenantUseCase tenants,
            OrganizationAdministrationUseCase administration) {
        this.organizations = organizations;
        this.tenants = tenants;
        this.administration = administration;
    }

    @PostMapping("/platform/organizations")
    ResponseEntity<AdministrativeOrganization> createOrganization(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @Valid @RequestBody NameRequest request) {
        var created = organizations.create(principal.userId(), request.name(), UUID.randomUUID());
        return ResponseEntity.created(URI.create("/platform/organizations/" + created.id()))
                .body(created);
    }

    @GetMapping("/platform/organizations")
    List<AdministrativeOrganization> listOrganizations(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return organizations.list(principal.userId());
    }

    @PutMapping("/platform/organizations/{organizationId}/suspension")
    ResponseEntity<Void> suspendOrganization(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @PathVariable UUID organizationId) {
        organizations.suspend(principal.userId(), organizationId, UUID.randomUUID());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/platform/organizations/{organizationId}/suspension")
    ResponseEntity<Void> recoverOrganization(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @PathVariable UUID organizationId) {
        organizations.recover(principal.userId(), organizationId, UUID.randomUUID());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/platform/tenants")
    ResponseEntity<AdministrativeTenant> createTenant(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @Valid @RequestBody NameRequest request) {
        var created = tenants.create(principal.userId(), request.name(), UUID.randomUUID());
        return ResponseEntity.created(URI.create("/platform/tenants/" + created.id()))
                .body(created);
    }

    @PutMapping("/platform/tenants/{tenantId}/suspension")
    ResponseEntity<Void> suspendTenant(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @PathVariable UUID tenantId) {
        tenants.suspend(principal.userId(), tenantId, UUID.randomUUID());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/platform/tenants/{tenantId}/suspension")
    ResponseEntity<Void> recoverTenant(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @PathVariable UUID tenantId) {
        tenants.recover(principal.userId(), tenantId, UUID.randomUUID());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/platform/organizations/{organizationId}/tenants/{tenantId}")
    ResponseEntity<Void> attachTenant(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @PathVariable UUID organizationId,
            @PathVariable UUID tenantId) {
        administration.attachTenant(principal.userId(), organizationId, tenantId, UUID.randomUUID());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/platform/organizations/{expectedSourceOrganizationId}/tenants/{tenantId}/moves")
    ResponseEntity<Void> moveTenant(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @PathVariable UUID expectedSourceOrganizationId,
            @PathVariable UUID tenantId,
            @Valid @RequestBody MoveTenantRequest request) {
        administration.moveTenant(principal.userId(), expectedSourceOrganizationId,
                request.destinationOrganizationId(), tenantId, UUID.randomUUID());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/platform/organizations/{expectedOrganizationId}/tenants/{tenantId}")
    ResponseEntity<Void> detachTenant(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @PathVariable UUID expectedOrganizationId,
            @PathVariable UUID tenantId) {
        administration.detachTenant(principal.userId(), expectedOrganizationId, tenantId, UUID.randomUUID());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/platform/organizations/{organizationId}/administrative-grants/{userId}/permissions/{permissionCode}")
    ResponseEntity<Void> grant(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @PathVariable UUID organizationId,
            @PathVariable UUID userId,
            @PathVariable String permissionCode) {
        administration.grant(principal.userId(), organizationId, userId, permissionCode, UUID.randomUUID());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/platform/organizations/{organizationId}/administrative-grants/{userId}/permissions/{permissionCode}")
    ResponseEntity<Void> revoke(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @PathVariable UUID organizationId,
            @PathVariable UUID userId,
            @PathVariable String permissionCode) {
        administration.revoke(principal.userId(), organizationId, userId, permissionCode, UUID.randomUUID());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/organizations/{organizationId}/tenants")
    List<OrganizationTenantSummary> listOrganizationTenants(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @PathVariable UUID organizationId) {
        return administration.listTenants(principal.userId(), organizationId);
    }

    public record NameRequest(
            @NotBlank @Size(max = 120) String name) {
    }

    public record MoveTenantRequest(
            @NotNull UUID destinationOrganizationId) {
    }
}
