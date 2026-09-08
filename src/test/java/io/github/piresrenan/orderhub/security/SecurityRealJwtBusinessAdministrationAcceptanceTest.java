package io.github.piresrenan.orderhub.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import io.github.piresrenan.orderhub.security.support.RealJwtTestSupport;
import io.github.piresrenan.orderhub.support.PostgreSqlTestConfiguration;

/**
 * Why: the public administration boundary must compose genuine authentication,
 * durable membership, Staff authority and owner persistence rather than mocks.
 * Covers: signed JWTs and isolated synthetic PostgreSQL identity/authority fixtures.
 * Prevents: individually green layers hiding a bypass at their actual composition.
 */
@SpringBootTest(properties = {
        "orderhub.security.jwt.issuer=https://business-admin.example.test",
        "orderhub.security.jwt.audience=orderhub-business-test",
        "orderhub.security.jwt.jwk-set-uri=http://127.0.0.1:1/business-admin-unused-jwks"
})
@AutoConfigureMockMvc
@Import({PostgreSqlTestConfiguration.class,
        SecurityRealJwtBusinessAdministrationAcceptanceTest.RealJwtConfiguration.class})
class SecurityRealJwtBusinessAdministrationAcceptanceTest {

    private static final String ISSUER = "https://business-admin.example.test";
    private static final String AUDIENCE = "orderhub-business-test";
    private static final RSAKey KEY = RealJwtTestSupport.generateRsaKey("business-administration-test");

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;

    /** Why: supplementary Unicode characters occupy two UTF-16 units but one contract code point.
     * Covers: exact and over-limit Product/Category names and descriptions on create and metadata update.
     * Prevents: transport rejecting domain-valid text or accepting genuinely overlong metadata. */
    @ParameterizedTest
    @CsvSource({"products,160,1","products,1,4000","categories,160,1","categories,1,4000"})
    void catalogTextLimitsCountCodePoints(String resource,int nameLength,int descriptionLength) throws Exception {
        var actor=member(); grantStaff(actor,"CATALOG_MANAGE");
        var id=UUID.randomUUID(); var name="😀".repeat(nameLength); var description="😀".repeat(descriptionLength);
        var input="\"name\":\"%s\",\"slug\":\"unicode\",\"description\":\"%s\"".formatted(name,description);
        mvc.perform(as(actor,post("/catalog/"+resource)).content("{\"id\":\""+id+"\","+input+"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.name").value(name));
        mvc.perform(as(actor,put("/catalog/"+resource+"/"+id+"/metadata")).content("{\"expectedRevision\":1,"+input+"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(2));
        var tooLongName=nameLength==160?name+"😀":name;
        var tooLongDescription=descriptionLength==4000?description+"😀":description;
        var over="{\"expectedRevision\":2,\"name\":\"%s\",\"slug\":\"unicode\",\"description\":\"%s\"}".formatted(tooLongName,tooLongDescription);
        mvc.perform(as(actor,put("/catalog/"+resource+"/"+id+"/metadata")).content(over)).andExpect(status().isBadRequest());
    }

    /** Why: sanitized responses alone do not prove application logging privacy.
     * Covers: authenticated invalid business input with distinctive credential and payload markers.
     * Prevents: owner error handlers logging bearer credentials, internal actors or raw bodies. */
    @Test
    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void rejectedBusinessInputDoesNotEnterApplicationLogs(org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        var actor=member(); grantStaff(actor,"CATALOG_MANAGE");
        var secret="synthetic-private-catalog-"+UUID.randomUUID();
        var token=RealJwtTestSupport.signedToken(KEY,ISSUER,actor.userId().toString(),AUDIENCE,
                Instant.now().plusSeconds(300),Instant.now().minusSeconds(30));
        var body="{\"id\":\"%s\",\"name\":\"%s\",\"slug\":\"/invalid\"}".formatted(UUID.randomUUID(),secret);
        var response=mvc.perform(post("/catalog/products").header("Authorization","Bearer "+token)
                .header("X-Tenant-Id",actor.tenantId()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andReturn().getResponse();
        assertSanitized(response.getContentAsString(),secret);
        assertThat(output.getAll()).doesNotContain(token,secret,actor.userId().toString(),actor.tenantId().toString());
    }

    /** Why: transport must expose the governed lifecycle and classifications without generic saves.
     * Covers: real Category assignment, Product/Variant transitions, stale metadata and foreign reads.
     * Prevents: thin route mappings bypassing ownership or application preconditions. */
    @Test void catalogCommandsPreserveLifecycleClassificationAndTenantScope() throws Exception {
        var actor=member(); grantStaff(actor,"CATALOG_MANAGE","CATALOG_VIEW");
        var variant=createVariant(actor);
        var product=jdbc.queryForObject("SELECT product_id FROM catalog.product_variants WHERE tenant_id=? AND id=?",UUID.class,actor.tenantId(),variant);
        var category=UUID.randomUUID();
        mvc.perform(as(actor,post("/catalog/categories")).content("{\"id\":\"%s\",\"name\":\"Category\",\"slug\":\"category\"}".formatted(category)))
                .andExpect(status().isCreated());
        mvc.perform(as(actor,put("/catalog/products/{id}/categories",product)).content(
                "{\"expectedRevision\":1,\"categoryIds\":[\"%s\"]}".formatted(category)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(2));
        mvc.perform(as(actor,post("/catalog/variants/{id}/activate",variant)).content("{\"expectedRevision\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
        mvc.perform(as(actor,post("/catalog/products/{id}/activate",product)).content("{\"expectedRevision\":2}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
        mvc.perform(as(actor,post("/catalog/variants/{id}/deactivate",variant)).content("{\"expectedRevision\":2}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("INACTIVE"));
        mvc.perform(as(actor,put("/catalog/products/{id}/metadata",product)).content(
                "{\"expectedRevision\":1,\"name\":\"Stale\",\"slug\":\"stale\"}" )).andExpect(status().isConflict());
        mvc.perform(as(actor,get("/catalog/products?limit=1"))).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(as(actor,get("/catalog/categories?limit=1"))).andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(category.toString()));
        var foreign=member(); grantStaff(foreign,"CATALOG_VIEW","CATALOG_MANAGE","INVENTORY_RECEIVE");
        for(var path:List.of("/catalog/products/"+product,"/catalog/variants/"+variant,"/catalog/categories/"+category))
            mvc.perform(as(foreign,get(path))).andExpect(status().isNotFound());
        mvc.perform(as(foreign,post("/inventory/receipts")).content(receiptBody(UUID.randomUUID(),variant,1))).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory.movements WHERE tenant_id=?",Integer.class,foreign.tenantId())).isZero();
    }

    /** Why: durable authorization uncertainty is distinct from a business denial.
     * Covers: corrupt durable ceiling through the real kernel and both owner adapters.
     * Prevents: uncertainty granting writes or leaking internal evidence in public problems. */
    @Test void technicalAuthorityFailureIsSanitizedAndWritesNothing() throws Exception {
        var actor=member(); grantStaff(actor,"CATALOG_MANAGE","INVENTORY_RECEIVE");
        jdbc.update("INSERT INTO workforce.job_position_permissions(tenant_id,position_id,permission_code) SELECT tenant_id,position_id,'SYNTHETIC_UNKNOWN_PERMISSION' FROM workforce.staff_placements WHERE tenant_id=?",actor.tenantId());
        var id=UUID.randomUUID();
        var response=mvc.perform(as(actor,post("/catalog/products")).content(productBody(id)))
                .andExpect(status().isInternalServerError()).andReturn().getResponse();
        assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertSanitized(response.getContentAsString(),"SYNTHETIC_UNKNOWN_PERMISSION");
        mvc.perform(as(actor,post("/inventory/receipts")).content(receiptBody(UUID.randomUUID(),UUID.randomUUID(),1)))
                .andExpect(status().isInternalServerError());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog.products WHERE tenant_id=?",Integer.class,actor.tenantId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory.movements WHERE tenant_id=?",Integer.class,actor.tenantId())).isZero();
    }

    /** Why: Inventory administration requires bounded, permission-protected current state and history.
     * Covers: detail, keyset limits, policy read, foreign absence and independent VIEW permission.
     * Prevents: write permissions implicitly granting enumeration or unbounded movement history. */
    @Test void inventoryReadsAreBoundedAndRequireIndependentViewPermission() throws Exception {
        var actor=member(); grantStaff(actor,"CATALOG_MANAGE","INVENTORY_RECEIVE","INVENTORY_VIEW","INVENTORY_POLICY_MANAGE");
        var variant=createVariant(actor); var operation=UUID.randomUUID();
        mvc.perform(as(actor,post("/inventory/receipts")).content(receiptBody(operation,variant,4))).andExpect(status().isCreated());
        mvc.perform(as(actor,get("/inventory/positions/{id}",variant))).andExpect(status().isOk()).andExpect(jsonPath("$.onHand").value(4));
        mvc.perform(as(actor,get("/inventory/positions?limit=1"))).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(as(actor,get("/inventory/positions/{id}/movements?limit=1",variant)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].operationId").value(operation.toString()));
        mvc.perform(as(actor,get("/inventory/positions/{id}/movements?afterOperationId={op}&limit=1",variant,operation)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(as(actor,get("/inventory/positions?limit=101"))).andExpect(status().isBadRequest());
        mvc.perform(as(actor,put("/inventory/policy")).content("{\"policy\":\"DENY\",\"reason\":\"POLICY_CHANGE\"}"))
                .andExpect(status().isOk());
        mvc.perform(as(actor,get("/inventory/policy"))).andExpect(status().isOk()).andExpect(jsonPath("$.policy").value("DENY"));
        var foreign=member(); grantStaff(foreign,"INVENTORY_VIEW");
        mvc.perform(as(foreign,get("/inventory/positions/{id}",variant))).andExpect(status().isNotFound());
        mvc.perform(as(foreign,get("/inventory/positions/{id}",UUID.randomUUID()))).andExpect(status().isNotFound());
        jdbc.update("DELETE FROM workforce.job_position_permissions WHERE tenant_id=? AND permission_code='INVENTORY_VIEW'",actor.tenantId());
        mvc.perform(as(actor,get("/inventory/positions/{id}",variant))).andExpect(status().isForbidden());
        mvc.perform(as(actor,get("/inventory/positions/{id}",UUID.randomUUID()))).andExpect(status().isForbidden());
    }

    /** Why: deny-only tests can hide miswired movement or identity adapters.
     * Covers: real receipt, exact replay, adjustment, desired safety/policy and current revocation.
     * Prevents: duplicate stock effects or historical permission authorizing a new retry. */
    @Test void receiptAndAdjustmentReplayExactlyAndRequireCurrentPermission() throws Exception {
        var actor=member();
        grantStaff(actor,"CATALOG_MANAGE","INVENTORY_RECEIVE","INVENTORY_ADJUST","INVENTORY_POLICY_MANAGE");
        var variant=createVariant(actor); var operation=UUID.randomUUID();
        var body=receiptBody(operation,variant,10);
        var first=mvc.perform(as(actor,post("/inventory/receipts")).content(body)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.delta").value(10)).andReturn().getResponse().getContentAsString();
        var retry=mvc.perform(as(actor,post("/inventory/receipts")).content(body)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        assertThat(retry).isEqualTo(first);
        mvc.perform(as(actor,post("/inventory/receipts")).content(receiptBody(operation,variant,11))).andExpect(status().isConflict());
        var adjustment="{\"operationId\":\"%s\",\"variantId\":\"%s\",\"delta\":-3,\"reason\":\"COUNT_CORRECTION\"}"
                .formatted(UUID.randomUUID(),variant);
        for(int n=0;n<2;n++) mvc.perform(as(actor,post("/inventory/adjustments")).content(adjustment)).andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT on_hand FROM inventory.inventory_positions WHERE tenant_id=? AND variant_id=?",Long.class,actor.tenantId(),variant)).isEqualTo(7);
        mvc.perform(as(actor,put("/inventory/positions/{id}/safety-stock",variant))
                .content("{\"expectedSafetyStock\":0,\"safetyStock\":2,\"reason\":\"THRESHOLD_CHANGE\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.safetyStock").value(2));
        var policy="{\"expectedPolicy\":null,\"policy\":\"DENY\",\"reason\":\"POLICY_CHANGE\"}";
        for(int n=0;n<2;n++) mvc.perform(as(actor,put("/inventory/policy")).content(policy)).andExpect(status().isOk());
        jdbc.update("DELETE FROM workforce.job_position_permissions WHERE tenant_id=? AND permission_code='INVENTORY_RECEIVE'",actor.tenantId());
        mvc.perform(as(actor,post("/inventory/receipts")).content(body)).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory.movements WHERE tenant_id=?",Integer.class,actor.tenantId())).isEqualTo(2);
    }

    /** Why: money and stock JSON must never truncate fractional or overflowing input.
     * Covers: exact large minor units, fractional/overflow stock and stale price revision.
     * Prevents: Jackson numeric coercion changing a financially material command. */
    @Test void numericTransportRemainsExactAndRejectsInvalidQuantities() throws Exception {
        var actor=member(); grantStaff(actor,"CATALOG_MANAGE","CATALOG_PRICE_MANAGE","CATALOG_VIEW","INVENTORY_RECEIVE");
        var variant=createVariant(actor);
        mvc.perform(as(actor,put("/catalog/variants/{id}/prices/BRL",variant))
                .content("{\"expectedRevision\":0,\"minorUnits\":9007199254740993}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.minorUnits").value(9007199254740993L));
        mvc.perform(as(actor,put("/catalog/variants/{id}/prices/BRL",variant))
                .content("{\"expectedRevision\":0,\"minorUnits\":1}" )).andExpect(status().isConflict());
        for(var quantity:List.of("1.1","9223372036854775808","-1","0","null")) {
            mvc.perform(as(actor,post("/inventory/receipts")).content("{\"operationId\":\"%s\",\"variantId\":\"%s\",\"quantity\":%s,\"reason\":\"RECEIPT\"}"
                    .formatted(UUID.randomUUID(),variant,quantity))).andExpect(status().isBadRequest());
        }
        mvc.perform(as(actor,put("/catalog/variants/{id}/prices/BRL",variant))
                .content("{\"expectedRevision\":1,\"minorUnits\":1.2}" )).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory.movements WHERE tenant_id=?",Integer.class,actor.tenantId())).isZero();
    }

    /** Creates actual Catalog identities through the same trusted HTTP boundary as a client. */
    private UUID createVariant(Actor actor) throws Exception {
        var product=UUID.randomUUID(); var variant=UUID.randomUUID();
        mvc.perform(as(actor,post("/catalog/products")).content(productBody(product))).andExpect(status().isCreated());
        mvc.perform(as(actor,post("/catalog/products/{id}/variants",product)).content(
                "{\"id\":\"%s\",\"sku\":\"SKU-%s\",\"attributes\":[]}".formatted(variant,variant))).andExpect(status().isCreated());
        return variant;
    }

    @Test
    void createsProductOnlyThroughRealIdentityMembershipStaffCeilingAndPermission() throws Exception {
        // Why: an authorized happy path proves every real boundary is composed.
        // Covers: valid RS256, durable identity/membership, ceiling AND grant, Catalog write.
        // Prevents: a test suite proving only that all requests are denied.
        var actor = member();
        grantStaff(actor, "CATALOG_MANAGE");
        var productId = UUID.randomUUID();
        mvc.perform(as(actor, post("/catalog/products")).content(productBody(productId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(productId.toString()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog.products WHERE tenant_id=? AND id=?",
                Integer.class, actor.tenantId(), productId)).isEqualTo(1);
    }

    @Test
    void authenticatedMemberWithoutStaffCannotCreateProduct() throws Exception {
        // Why: entering a Tenant does not grant its business administration.
        // Covers: real authenticated membership with no Staff relationship or grants.
        // Prevents: authenticated Users and CUSTOMER-only members becoming Catalog managers.
        var actor = member();
        var productId = UUID.randomUUID();
        mvc.perform(as(actor, post("/catalog/products")).content(productBody(productId)))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog.products WHERE tenant_id=? AND id=?",
                Integer.class, actor.tenantId(), productId)).isZero();
    }

    @ParameterizedTest
    @CsvSource({"/catalog/products", "/inventory/receipts"})
    void noBearerCannotReachBusinessAdministration(String path) throws Exception {
        // Why: every owner remains behind the production Resource Server chain.
        // Covers: missing bearer on both independently owned business boundaries.
        // Prevents: HTTP adapter registration accidentally creating a public mutation.
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void customerRelationshipDoesNotGrantStaffAdministration() throws Exception {
        // Why: Customer ownership is separate from a workforce relationship.
        // Covers: a real bound Customer account and valid Tenant membership without Staff.
        // Prevents: self-service identity granting Catalog or Inventory authority.
        var actor = member();
        var customerId = UUID.randomUUID();
        jdbc.update("INSERT INTO customers.customer_profiles(tenant_id,customer_id) VALUES (?,?)",
                actor.tenantId(), customerId);
        jdbc.update("INSERT INTO customers.customer_account_bindings(tenant_id,customer_id,user_id) VALUES (?,?,?)",
                actor.tenantId(), customerId, actor.userId());
        mvc.perform(as(actor, post("/catalog/products")).content(productBody(UUID.randomUUID())))
                .andExpect(status().isForbidden());
        mvc.perform(as(actor, post("/inventory/receipts")).content(receiptBody(UUID.randomUUID(), UUID.randomUUID(), 10)))
                .andExpect(status().isForbidden());
    }

    @Test
    void exactMembershipAndActiveTenantRemainMandatoryDespitePermission() throws Exception {
        // Why: a valid Staff grant cannot bypass trust in the requested Tenant.
        // Covers: suspended Tenant, a foreign selector, and subsequently absent membership.
        // Prevents: duplicated controller checks or JWT/path selectors becoming authority.
        var actor = member();
        grantStaff(actor, "CATALOG_MANAGE", "INVENTORY_RECEIVE");
        jdbc.update("UPDATE tenants.tenants SET status='SUSPENDED' WHERE id=?", actor.tenantId());
        mvc.perform(as(actor, post("/catalog/products")).content(productBody(UUID.randomUUID())))
                .andExpect(status().isForbidden());
        mvc.perform(as(actor, post("/inventory/receipts")).content(receiptBody(UUID.randomUUID(), UUID.randomUUID(), 10)))
                .andExpect(status().isForbidden());
        jdbc.update("UPDATE tenants.tenants SET status='ACTIVE' WHERE id=?", actor.tenantId());
        var foreign = member();
        mvc.perform(as(new Actor(actor.userId(), foreign.tenantId()), post("/catalog/products"))
                .content(productBody(UUID.randomUUID()))).andExpect(status().isForbidden());
        jdbc.update("DELETE FROM users.tenant_memberships WHERE tenant_id=? AND user_id=?",
                actor.tenantId(), actor.userId());
        mvc.perform(as(actor, post("/catalog/products")).content(productBody(UUID.randomUUID())))
                .andExpect(status().isForbidden());
    }

    @Test
    void permissionInAnotherTenantCannotBeUsedEvenWithMembershipInBoth() throws Exception {
        // Why: membership in multiple Tenants must not make a role global.
        // Covers: current user belongs to A and B, but has a Catalog role only in A.
        // Prevents: role or organizational ceiling lookup omitting its Tenant predicate.
        var actor = member();
        grantStaff(actor, "CATALOG_MANAGE");
        var foreign = member();
        jdbc.update("INSERT INTO users.tenant_memberships(user_id,tenant_id) VALUES (?,?)",
                actor.userId(), foreign.tenantId());
        mvc.perform(as(new Actor(actor.userId(), foreign.tenantId()), post("/catalog/products"))
                .content(productBody(UUID.randomUUID()))).andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @CsvSource({
        "CATALOG_MANAGE,RECEIVE", "INVENTORY_RECEIVE,ADJUST", "INVENTORY_ADJUST,POLICY",
        "INVENTORY_POLICY_MANAGE,CATALOG", "CATALOG_VIEW,CATALOG", "CATALOG_MANAGE,PRICE"
    })
    void atomicPermissionsHaveNoImplicitFamilyInheritance(String permission, String operation) throws Exception {
        // Why: financially material permissions are intentionally independent.
        // Covers: exact durable ceiling/grant for one capability against another valid command.
        // Prevents: wildcard Catalog/Inventory authority or accidental privilege inheritance.
        var actor = member();
        grantStaff(actor, permission);
        var variantId = UUID.randomUUID();
        var request = switch (operation) {
            case "RECEIVE" -> post("/inventory/receipts")
                    .content(receiptBody(UUID.randomUUID(), variantId, 1));
            case "ADJUST" -> post("/inventory/adjustments").content("""
                    {"operationId":"%s","variantId":"%s","delta":1,"reason":"COUNT_CORRECTION"}
                    """.formatted(UUID.randomUUID(), variantId));
            case "POLICY" -> put("/inventory/policy")
                    .content("{\"expectedPolicy\":null,\"policy\":\"DENY\",\"reason\":\"POLICY_CHANGE\"}");
            case "PRICE" -> put("/catalog/variants/{id}/prices/BRL", variantId)
                    .content("{\"expectedRevision\":0,\"minorUnits\":100}");
            default -> post("/catalog/products").content(productBody(UUID.randomUUID()));
        };
        mvc.perform(as(actor, request)).andExpect(status().isForbidden());
    }

    @Test
    void signedForgedRoleAndTenantClaimsCannotGrantBusinessPermission() throws Exception {
        // Why: even authentic provider claims are not the durable business permission source.
        // Covers: correctly signed JWT with invented STAFF/role/permission/Tenant claims.
        // Prevents: resource-server authentication leaking JWT authority into Tenant policy.
        var actor = member();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(),
                new JWTClaimsSet.Builder().issuer(ISSUER).subject(actor.userId().toString()).audience(AUDIENCE)
                        .notBeforeTime(Date.from(Instant.now().minusSeconds(30)))
                        .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                        .claim("tenantId", actor.tenantId().toString()).claim("persona", "STAFF")
                        .claim("roles", List.of("TENANT_ADMINISTRATOR"))
                        .claim("permissions", List.of("CATALOG_MANAGE", "INVENTORY_RECEIVE")).build());
        jwt.sign(new RSASSASigner(KEY));
        mvc.perform(post("/catalog/products").header("Authorization", "Bearer " + jwt.serialize())
                .header("X-Tenant-Id", actor.tenantId()).contentType(MediaType.APPLICATION_JSON)
                .content(productBody(UUID.randomUUID()))).andExpect(status().isForbidden());
    }

    @Test
    void platformAndOrganizationGrantsCannotBecomeTenantStaffAuthority() throws Exception {
        // Why: control-plane authority does not grant Tenant-private business access.
        // Covers: actual PLATFORM and ORGANIZATION grants on an authenticated member.
        // Prevents: OH-017 administrative capabilities being interpreted as Staff permissions.
        var actor = member();
        jdbc.update("""
                INSERT INTO access_control.administrative_grants(grant_id,user_id,scope_type,scope_id,permission_code)
                VALUES (?,?,'PLATFORM',NULL,'PLATFORM_TENANTS_MANAGE')
                """, UUID.randomUUID(), actor.userId());
        jdbc.update("""
                INSERT INTO access_control.administrative_grants(grant_id,user_id,scope_type,scope_id,permission_code)
                VALUES (?,?,'ORGANIZATION',?,'ORGANIZATION_TENANTS_VIEW')
                """, UUID.randomUUID(), actor.userId(), UUID.randomUUID());
        mvc.perform(as(actor, post("/catalog/products")).content(productBody(UUID.randomUUID())))
                .andExpect(status().isForbidden());
    }

    @Test
    void forgedBodyTenantCannotSelectTheOwnerOfCreatedProduct() throws Exception {
        // Why: DTO extras must never override reconciled Tenant authority.
        // Covers: a body Tenant UUID differing from the trusted selector.
        // Prevents: accidental mass assignment of resource ownership from request JSON.
        var actor = member();
        grantStaff(actor, "CATALOG_MANAGE");
        var foreign = member();
        var id = UUID.randomUUID();
        var body = productBody(id).strip().replace("}", ",\"tenantId\":\"" + foreign.tenantId() + "\"}");
        var response = mvc.perform(as(actor, post("/catalog/products")).content(body)).andReturn().getResponse();
        assertThat(response.getStatus()).isIn(201, 400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog.products WHERE tenant_id=? AND id=?",
                Integer.class, foreign.tenantId(), id)).isZero();
        if (response.getStatus() == 201) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog.products WHERE tenant_id=? AND id=?",
                    Integer.class, actor.tenantId(), id)).isEqualTo(1);
        }
    }

    @Test
    void frameworkNegotiationStatusIsPreservedAndSanitized() throws Exception {
        // Why: legitimate Spring MVC rejection must not be rewritten by a catch-all.
        // Covers: unsupported content type and unacceptable response representation.
        // Prevents: OH-017's 406/415-to-false-500 regression at new owner adapters.
        var actor = member();
        grantStaff(actor, "CATALOG_MANAGE", "INVENTORY_RECEIVE");
        for (var path : List.of("/catalog/products", "/inventory/receipts")) {
            var unsupported = mvc.perform(as(actor, post(path)).contentType(MediaType.TEXT_PLAIN).content("synthetic-private-body"))
                    .andExpect(status().isUnsupportedMediaType()).andReturn().getResponse();
            assertSanitized(unsupported.getContentAsString(), "synthetic-private-body");
            var unacceptable = mvc.perform(as(actor, post(path)).accept(MediaType.APPLICATION_XML).content("{}"))
                    .andExpect(status().isNotAcceptable()).andReturn().getResponse();
            assertSanitized(unacceptable.getContentAsString(), actor.userId().toString());
        }
    }

    /** Checks constant public errors without accepting SQL/schema/private request disclosure. */
    private void assertSanitized(String body, String privateValue) {
        assertThat(body).doesNotContain(privateValue, "org.springframework", "java.sql", "SELECT ",
                "INSERT ", "access_control.", "workforce.", "stackTrace", "Authorization", "Bearer ");
    }

    /** Builds a stable synthetically identified receipt command for real HTTP acceptance. */
    private String receiptBody(UUID operationId, UUID variantId, long quantity) {
        return """
                {"operationId":"%s","variantId":"%s","quantity":%d,"reason":"STOCK_RECEIVED"}
                """.formatted(operationId, variantId, quantity);
    }

    /** Creates a real internal identity and one exact active Tenant membership. */
    private Actor member() {
        var actor = new Actor(UUID.randomUUID(), UUID.randomUUID());
        jdbc.update("INSERT INTO tenants.tenants(id,name,status) VALUES (?,'Synthetic business tenant','ACTIVE')", actor.tenantId());
        jdbc.update("INSERT INTO users.users(id) VALUES (?)", actor.userId());
        jdbc.update("INSERT INTO users.external_identity_bindings(issuer,subject,user_id) VALUES (?,?,?)",
                ISSUER, actor.userId().toString(), actor.userId());
        jdbc.update("INSERT INTO users.tenant_memberships(user_id,tenant_id) VALUES (?,?)",
                actor.userId(), actor.tenantId());
        return actor;
    }

    /** Seeds both the organizational ceiling and an independent durable role grant. */
    private void grantStaff(Actor actor, String... permissions) {
        var staffId = UUID.randomUUID();
        var departmentId = UUID.randomUUID();
        var positionId = UUID.randomUUID();
        var roleId = UUID.randomUUID();
        var roleCode = "OH18_" + roleId.toString().replace("-", "").toUpperCase(java.util.Locale.ROOT);
        jdbc.update("INSERT INTO workforce.staff_profiles(staff_id,user_id,tenant_id,status) VALUES (?,?,?,'ACTIVE')",
                staffId, actor.userId(), actor.tenantId());
        jdbc.update("INSERT INTO workforce.departments(department_id,tenant_id,code,name) VALUES (?,?,'BUSINESS','Business')",
                departmentId, actor.tenantId());
        jdbc.update("""
                INSERT INTO workforce.job_positions(position_id,tenant_id,code,title,authority_band)
                VALUES (?,?,'BUSINESS','Business administrator','MANAGEMENT')
                """, positionId, actor.tenantId());
        jdbc.update("INSERT INTO workforce.staff_placements(tenant_id,staff_id,department_id,position_id) VALUES (?,?,?,?)",
                actor.tenantId(), staffId, departmentId, positionId);
        jdbc.update("""
                INSERT INTO access_control.role_definitions(role_id,tenant_id,code,persona,authority_band,mutability)
                VALUES (?,?,?,'STAFF','MANAGEMENT','TENANT_CUSTOM')
                """, roleId, actor.tenantId(), roleCode);
        for (var permission : permissions) {
            jdbc.update("INSERT INTO workforce.job_position_permissions(tenant_id,position_id,permission_code) VALUES (?,?,?)",
                    actor.tenantId(), positionId, permission);
            jdbc.update("INSERT INTO access_control.role_permissions(role_id,permission_code) VALUES (?,?)",
                    roleId, permission);
        }
        jdbc.update("INSERT INTO access_control.role_assignments(assignment_id,user_id,tenant_id,persona,role_id) VALUES (?,?,?,'STAFF',?)",
                UUID.randomUUID(), actor.userId(), actor.tenantId(), roleId);
    }

    /** Signs a real synthetic bearer while retaining production JWT validation. */
    private MockHttpServletRequestBuilder as(Actor actor, MockHttpServletRequestBuilder request) throws JOSEException {
        var token = RealJwtTestSupport.signedToken(KEY, ISSUER, actor.userId().toString(), AUDIENCE,
                Instant.now().plusSeconds(300), Instant.now().minusSeconds(30));
        return request.header("Authorization", "Bearer " + token)
                .header("X-Tenant-Id", actor.tenantId())
                .contentType(MediaType.APPLICATION_JSON);
    }

    /** Produces bounded synthetic metadata with no tenant or permission authority in JSON. */
    private String productBody(UUID id) {
        return """
                {"id":"%s","name":"Synthetic product","slug":"p-%s","description":null,"brand":null}
                """.formatted(id, id);
    }

    private record Actor(UUID userId, UUID tenantId) { }

    @TestConfiguration(proxyBeanMethods = false)
    static class RealJwtConfiguration {
        /** Uses only an ephemeral test public key; all application authorization remains real. */
        @Bean @Primary
        JwtDecoder businessAdministrationJwtDecoder() throws JOSEException {
            return RealJwtTestSupport.decoder(KEY, ISSUER, AUDIENCE);
        }
    }
}
