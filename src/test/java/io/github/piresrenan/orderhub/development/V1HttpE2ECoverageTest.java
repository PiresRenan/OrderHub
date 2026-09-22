package io.github.piresrenan.orderhub.development;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.springframework.boot.web.server.context.WebServerApplicationContext;

import tools.jackson.databind.ObjectMapper;

/**
 * Why: every admitted v1 HTTP operation must keep a real-socket end-to-end proof.
 * Covers: the operation set actually served by the running application against an explicit per-operation
 * coverage manifest whose entries must resolve to existing {@code @Test} methods.
 * Prevents: a future business endpoint shipping without classified E2E coverage, and "covered" wildcards.
 */
class V1HttpE2ECoverageTest {
    private static final String SPE = "SeededPlatformAdministrationE2ETest#";
    private static final String SCE = "SeededCatalogE2ETest#";
    private static final String SIO = "SeededInventoryOrderE2ETest#";
    private static final String SIL = "SeededIdentityLifecycleE2ETest#";
    private static final String SXE = "SeededExternalIdentityE2ETest#";

    /** One primary proving test per operation; shared boundaries are documented in the local HTTP matrix. */
    static final Map<String, String> COVERAGE = coverage();

    private static Map<String, String> coverage() {
        var map = new LinkedHashMap<String, String>();
        map.put("platformListOrganizations", SPE + "platformRoutesRequirePlatformAuthorityAndPlatformHasNoTenantBusinessAccess");
        map.put("platformCreateOrganization", SPE + "organizationSuspensionIsIdempotentAndVisible");
        map.put("platformSuspendOrganization", SPE + "organizationSuspensionIsIdempotentAndVisible");
        map.put("platformRecoverOrganization", SPE + "organizationSuspensionIsIdempotentAndVisible");
        map.put("platformSuspendTenant", SPE + "tenantSuspensionClosesAndRecoveryReopensOnlyThatTenant");
        map.put("platformRecoverTenant", SPE + "tenantSuspensionClosesAndRecoveryReopensOnlyThatTenant");
        map.put("platformCreateTenant", SPE + "placementsAttachMoveAndDetachWithConflicts");
        map.put("platformAttachTenant", SPE + "placementsAttachMoveAndDetachWithConflicts");
        map.put("platformMoveTenant", SPE + "placementsAttachMoveAndDetachWithConflicts");
        map.put("platformDetachTenant", SPE + "placementsAttachMoveAndDetachWithConflicts");
        map.put("organizationListTenants", SPE + "organizationGrantsControlAntiEnumeratingTenantListing");
        map.put("platformGrantOrganizationPermission", SPE + "organizationGrantsControlAntiEnumeratingTenantListing");
        map.put("platformRevokeOrganizationPermission", SPE + "organizationGrantsControlAntiEnumeratingTenantListing");
        map.put("catalogProducts", SCE + "readsAreTenantIsolatedDespiteIdenticalBusinessIdentifiers");
        map.put("catalogProduct", SCE + "readsAreTenantIsolatedDespiteIdenticalBusinessIdentifiers");
        map.put("catalogCategories", SCE + "readsAreTenantIsolatedDespiteIdenticalBusinessIdentifiers");
        map.put("catalogVariants", SCE + "readsAreTenantIsolatedDespiteIdenticalBusinessIdentifiers");
        map.put("catalogVariant", SCE + "readsAreTenantIsolatedDespiteIdenticalBusinessIdentifiers");
        map.put("catalogCategory", SCE + "categoryHierarchyRejectsDuplicatesStaleRevisionsAndCycles");
        map.put("catalogCreateCategory", SCE + "categoryHierarchyRejectsDuplicatesStaleRevisionsAndCycles");
        map.put("catalogUpdateCategory", SCE + "categoryHierarchyRejectsDuplicatesStaleRevisionsAndCycles");
        map.put("catalogReparentCategory", SCE + "categoryHierarchyRejectsDuplicatesStaleRevisionsAndCycles");
        map.put("catalogCreateProduct", SCE + "productAndVariantStateMachinesEnforceTransitionsAndTerminalArchive");
        map.put("catalogUpdateProduct", SCE + "productAndVariantStateMachinesEnforceTransitionsAndTerminalArchive");
        map.put("catalogAssignCategories", SCE + "productAndVariantStateMachinesEnforceTransitionsAndTerminalArchive");
        map.put("catalogActivateProduct", SCE + "productAndVariantStateMachinesEnforceTransitionsAndTerminalArchive");
        map.put("catalogArchiveProduct", SCE + "productAndVariantStateMachinesEnforceTransitionsAndTerminalArchive");
        map.put("catalogCreateVariant", SCE + "productAndVariantStateMachinesEnforceTransitionsAndTerminalArchive");
        map.put("catalogUpdateVariant", SCE + "productAndVariantStateMachinesEnforceTransitionsAndTerminalArchive");
        map.put("catalogActivateVariant", SCE + "productAndVariantStateMachinesEnforceTransitionsAndTerminalArchive");
        map.put("catalogDeactivateVariant", SCE + "productAndVariantStateMachinesEnforceTransitionsAndTerminalArchive");
        map.put("catalogArchiveVariant", SCE + "productAndVariantStateMachinesEnforceTransitionsAndTerminalArchive");
        map.put("catalogPrice", SCE + "pricesAreRevisionedAndRequirePriceAuthority");
        map.put("catalogSetPrice", SCE + "pricesAreRevisionedAndRequirePriceAuthority");
        map.put("inventoryListPositions", SIO + "inventoryReadsReflectTheSeedAndAreTenantIsolated");
        map.put("inventoryGetPosition", SIO + "inventoryReadsReflectTheSeedAndAreTenantIsolated");
        map.put("inventoryListMovements", SIO + "inventoryReadsReflectTheSeedAndAreTenantIsolated");
        map.put("inventoryGetPolicy", SIO + "inventoryReadsReflectTheSeedAndAreTenantIsolated");
        map.put("inventoryRecordReceipt", SIO + "movementsAreIdempotentValidatedAndConfinedToTheirTenant");
        map.put("inventoryRecordAdjustment", SIO + "movementsAreIdempotentValidatedAndConfinedToTheirTenant");
        map.put("inventorySetPolicy", SIO + "policyAndSafetyStockUseOptimisticExpectations");
        map.put("inventorySetSafetyStock", SIO + "policyAndSafetyStockUseOptimisticExpectations");
        map.put("ordersCreate", SIO + "ordersAllocateByTenantPolicyAndReplayIdempotently");
        map.put("ordersView", SIO + "ordersAreVisibleOnlyToTheirCustomerInTheirTenant");
        map.put("identityIssueStaffProvisioning", SIL + "normalStaffProvisioningUsesThePublishedPlacementAndHonoursCancellation");
        map.put("identityCancelStaffProvisioning", SIL + "normalStaffProvisioningUsesThePublishedPlacementAndHonoursCancellation");
        map.put("identitySuspendMembership", SIL + "membershipLifecycleIsIdempotentScopedAndTerminal");
        map.put("identityRecoverMembership", SIL + "membershipLifecycleIsIdempotentScopedAndTerminal");
        map.put("identityTerminateMembership", SIL + "membershipLifecycleIsIdempotentScopedAndTerminal");
        map.put("identityIssueCustomerAccountProof", SIL + "customerAccountLinkProofsAreReplayableCancellableAndTenantBound");
        map.put("identityCancelCustomerAccountProof", SIL + "customerAccountLinkProofsAreReplayableCancellableAndTenantBound");
        map.put("identityConsumeCustomerAccountProof", SIL + "customerAccountLinkProofsAreReplayableCancellableAndTenantBound");
        map.put("identityIssueInitialStaffProvisioning", SIL + "initialStaffCeremonyEstablishesTheFirstStaffOfTheEmptyTenantOnce");
        map.put("identityCancelInitialStaffProvisioning", SIL + "initialStaffCeremonyEstablishesTheFirstStaffOfTheEmptyTenantOnce");
        map.put("identityBootstrapStaff", SIL + "initialStaffCeremonyEstablishesTheFirstStaffOfTheEmptyTenantOnce");
        map.put("identityListExternalAccounts", SXE + "externalIdentityLinkLifecycleKeepsOneInternalUser");
        map.put("identityIssueExternalLinkProof", SXE + "externalIdentityLinkLifecycleKeepsOneInternalUser");
        map.put("identityCancelExternalLinkProof", SXE + "externalIdentityLinkLifecycleKeepsOneInternalUser");
        map.put("identityBootstrapExternalLink", SXE + "externalIdentityLinkLifecycleKeepsOneInternalUser");
        map.put("identityUnlinkExternalAccount", SXE + "externalIdentityLinkLifecycleKeepsOneInternalUser");
        return java.util.Collections.unmodifiableMap(map);
    }

    @Test
    void everyServedBusinessOperationHasAnExistingRealSocketE2ETest() throws Exception {
        try (var context = LocalDevelopmentApplication.start(0, 0)) {
            var app = "http://127.0.0.1:" + ((WebServerApplicationContext) context).getWebServer().getPort();
            var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(app + "/v3/api-docs")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            var served = new TreeSet<String>();
            var paths = new ObjectMapper().readTree(response.body()).get("paths");
            for (var path : paths.propertyNames()) {
                // Health and documentation are operational endpoints, never part of the business contract.
                assertThat(path).doesNotStartWith("/actuator").doesNotStartWith("/livez").doesNotStartWith("/readyz")
                        .doesNotStartWith("/v3").doesNotStartWith("/swagger");
                for (var method : paths.get(path).propertyNames()) {
                    served.add(paths.get(path).get(method).get("operationId").asString());
                }
            }
            assertThat(served).containsExactlyInAnyOrderElementsOf(COVERAGE.keySet());
        }
        for (var entry : COVERAGE.entrySet()) {
            assertThat(entry.getValue()).as(entry.getKey()).matches("^Seeded[A-Za-z]+E2ETest#[a-z][A-Za-z]+$");
            var parts = entry.getValue().split("#");
            var type = Class.forName(V1HttpE2ECoverageTest.class.getPackageName() + "." + parts[0]);
            var method = type.getDeclaredMethod(parts[1]);
            assertThat(method.isAnnotationPresent(Test.class)).as(entry.getValue()).isTrue();
        }
    }
}
