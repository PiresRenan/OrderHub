package io.github.piresrenan.orderhub.administration.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.MethodParameter;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import io.github.piresrenan.orderhub.organizations.application.port.in.administration.AdministrativeOrganization;
import io.github.piresrenan.orderhub.organizations.application.port.in.administration.OrganizationAdministrationUseCase;
import io.github.piresrenan.orderhub.organizations.application.port.in.administration.OrganizationTenantSummary;
import io.github.piresrenan.orderhub.organizations.application.port.in.administration.PlatformAdministrationAccessDeniedException;
import io.github.piresrenan.orderhub.organizations.application.port.in.administration.PlatformOrganizationUseCase;
import io.github.piresrenan.orderhub.security.adapter.in.authentication.AuthenticatedUserAuthenticationToken;
import io.github.piresrenan.orderhub.security.application.model.AuthenticatedUserPrincipal;
import io.github.piresrenan.orderhub.tenants.application.port.in.administration.PlatformTenantUseCase;
import io.github.piresrenan.orderhub.tenants.application.port.in.administration.AdministrativeTenant;

@WebMvcTest(AdministrationController.class)
@Import(AdministrationControllerTest.PrincipalResolverConfiguration.class)
class AdministrationControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private PlatformOrganizationUseCase organizations;

    @MockitoBean
    private PlatformTenantUseCase tenants;

    @MockitoBean
    private OrganizationAdministrationUseCase administration;

    @Test
    void platformRouteUsesInternalPrincipalWithoutTenantHeader() throws Exception {
        var id = UUID.randomUUID();
        when(organizations.create(any(), any(), any()))
                .thenReturn(new AdministrativeOrganization(id, "Group", "ACTIVE"));

        mvc.perform(authenticated(post("/platform/organizations"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Group\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/platform/organizations/" + id))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        verify(organizations).create(any(UUID.class), any(String.class), any(UUID.class));
    }

    @Test
    void validatesOrganizationNamesAfterStrippingByUnicodeCodePoint() throws Exception {
        var name = " " + "😀".repeat(120) + " ";
        var id = UUID.randomUUID();
        when(organizations.create(any(), any(), any()))
                .thenReturn(new AdministrativeOrganization(id, name.strip(), "ACTIVE"));

        mvc.perform(authenticated(post("/platform/organizations"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated());

        mvc.perform(authenticated(post("/platform/organizations"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + "😀".repeat(121) + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deniedTargetProducesSanitizedProblemDetails() throws Exception {
        when(organizations.list(any())).thenThrow(new PlatformAdministrationAccessDeniedException());

        mvc.perform(authenticated(get("/platform/organizations")))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("administration-access-denied"))
                .andExpect(content().string(not(containsString("grant"))))
                .andExpect(content().string(not(containsString("SQL"))));
    }

    @Test
    void organizationListingContainsOnlyBoundedMetadata() throws Exception {
        var organizationId = UUID.randomUUID();
        var tenantId = UUID.randomUUID();
        when(administration.listTenants(any(), any()))
                .thenReturn(List.of(new OrganizationTenantSummary(
                        tenantId, "Store", "SUSPENDED")));

        mvc.perform(authenticated(get("/organizations/{id}/tenants", organizationId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(tenantId.toString()))
                .andExpect(jsonPath("$[0].name").value("Store"))
                .andExpect(jsonPath("$[0].status").value("SUSPENDED"))
                .andExpect(content().string(not(containsString("orders"))))
                .andExpect(content().string(not(containsString("inventory"))))
                .andExpect(content().string(not(containsString("customers"))));
    }

    @Test
    void technicalFailureIsNotMisreportedAsPolicyDenial() throws Exception {
        when(organizations.list(any())).thenThrow(new IllegalStateException("SQL secret"));

        mvc.perform(authenticated(get("/platform/organizations")))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("administration-technical-failure"))
                .andExpect(content().string(not(containsString("SQL secret"))));
    }

    @Test
    void malformedPathAndJsonAreStableSanitizedClientErrors() throws Exception {
        mvc.perform(authenticated(put("/platform/organizations/not-a-uuid/suspension")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid-administration-request"));

        mvc.perform(authenticated(post("/platform/organizations"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid-administration-request"))
                .andExpect(content().string(not(containsString("not-json"))));
    }

    @Test
    void exposesEveryRequiredMutationRoute() throws Exception {
        var organization = UUID.randomUUID();
        var destination = UUID.randomUUID();
        var tenant = UUID.randomUUID();
        var user = UUID.randomUUID();
        when(tenants.create(any(), any(), any()))
                .thenReturn(new AdministrativeTenant(tenant, "Store", "ACTIVE"));

        mvc.perform(authenticated(post("/platform/tenants"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Store\"}"))
                .andExpect(status().isCreated());
        mvc.perform(authenticated(put("/platform/organizations/{id}/suspension", organization)))
                .andExpect(status().isNoContent());
        mvc.perform(authenticated(delete("/platform/organizations/{id}/suspension", organization)))
                .andExpect(status().isNoContent());
        mvc.perform(authenticated(put("/platform/tenants/{id}/suspension", tenant)))
                .andExpect(status().isNoContent());
        mvc.perform(authenticated(delete("/platform/tenants/{id}/suspension", tenant)))
                .andExpect(status().isNoContent());
        mvc.perform(authenticated(put("/platform/organizations/{org}/tenants/{tenant}",
                        organization, tenant)))
                .andExpect(status().isNoContent());
        mvc.perform(authenticated(post(
                        "/platform/organizations/{org}/tenants/{tenant}/moves",
                        organization, tenant))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"destinationOrganizationId\":\"" + destination + "\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(authenticated(delete("/platform/organizations/{org}/tenants/{tenant}",
                        organization, tenant)))
                .andExpect(status().isNoContent());
        var grantPath = "/platform/organizations/{org}/administrative-grants/{user}/permissions/{permission}";
        mvc.perform(authenticated(put(grantPath, organization, user, "ORGANIZATION_TENANTS_VIEW")))
                .andExpect(status().isNoContent());
        mvc.perform(authenticated(delete(grantPath, organization, user, "ORGANIZATION_TENANTS_VIEW")))
                .andExpect(status().isNoContent());
    }

    private static MockHttpServletRequestBuilder authenticated(
            MockHttpServletRequestBuilder request) {
        return request.principal(new AuthenticatedUserAuthenticationToken(
                new AuthenticatedUserPrincipal(UUID.randomUUID())));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class PrincipalResolverConfiguration {
        @Bean
        WebMvcConfigurer principalResolverConfigurer() {
            return new WebMvcConfigurer() {
                @Override
                public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
                    resolvers.add(new HandlerMethodArgumentResolver() {
                        @Override
                        public boolean supportsParameter(MethodParameter parameter) {
                            return parameter.getParameterType() == AuthenticatedUserPrincipal.class;
                        }

                        @Override
                        public Object resolveArgument(MethodParameter parameter,
                                ModelAndViewContainer container, NativeWebRequest request,
                                WebDataBinderFactory binderFactory) {
                            var authentication = (AuthenticatedUserAuthenticationToken)
                                    request.getUserPrincipal();
                            return authentication.getPrincipal();
                        }
                    });
                }
            };
        }
    }
}
