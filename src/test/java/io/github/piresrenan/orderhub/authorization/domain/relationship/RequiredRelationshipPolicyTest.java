package io.github.piresrenan.orderhub.authorization.domain.relationship;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.piresrenan.orderhub.authorization.domain.model.AuthorizationDecision;
import io.github.piresrenan.orderhub.authorization.domain.model.AuthorizationPersona;
import io.github.piresrenan.orderhub.authorization.domain.model.TenantAuthorizationScope;

class RequiredRelationshipPolicyTest {

    private final RequiredRelationshipPolicy customerOwnerPolicy =
            new RequiredRelationshipPolicy(
                    AuthorizationPersona.CUSTOMER,
                    AuthorizationRelationship.RESOURCE_OWNER);

    @Test
    void allowsMatchingCustomerRelationshipFact() {

        var decision =
                customerOwnerPolicy.evaluate(
                        context(
                                AuthorizationPersona.CUSTOMER,
                                Set.of(
                                        AuthorizationRelationship.RESOURCE_OWNER)));

        assertThat(decision)
                .isEqualTo(
                        AuthorizationDecision.ALLOW);
    }

    @Test
    void deniesWhenRequiredRelationshipIsAbsent() {

        var decision =
                customerOwnerPolicy.evaluate(
                        context(
                                AuthorizationPersona.CUSTOMER,
                                Set.of()));

        assertThat(decision)
                .isEqualTo(
                        AuthorizationDecision.DENY);
    }

    @Test
    void sameRelationshipFactDoesNotLeakAcrossPersonas() {

        var decision =
                customerOwnerPolicy.evaluate(
                        context(
                                AuthorizationPersona.STAFF,
                                Set.of(
                                        AuthorizationRelationship.RESOURCE_OWNER)));

        assertThat(decision)
                .isEqualTo(
                        AuthorizationDecision.DENY);
    }

    @Test
    void relationshipFactsAreDefensivelyCopiedAndRejectMalformedState() {

        var mutableRelationships =
                new HashSet<AuthorizationRelationship>();

        mutableRelationships.add(
                AuthorizationRelationship.RESOURCE_OWNER);

        var context =
                context(
                        AuthorizationPersona.CUSTOMER,
                        mutableRelationships);

        mutableRelationships.clear();

        assertThat(
                context.relationships())
                .containsExactly(
                        AuthorizationRelationship.RESOURCE_OWNER);

        assertThatThrownBy(() ->
                new RelationshipAuthorizationContext(
                        UUID.randomUUID(),
                        AuthorizationPersona.CUSTOMER,
                        new TenantAuthorizationScope(
                                UUID.randomUUID()),
                        null))
                .isInstanceOf(
                        IllegalArgumentException.class);
    }

    private static RelationshipAuthorizationContext context(
            AuthorizationPersona persona,
            Set<AuthorizationRelationship> relationships) {

        return new RelationshipAuthorizationContext(
                UUID.randomUUID(),
                persona,
                new TenantAuthorizationScope(
                        UUID.randomUUID()),
                relationships);
    }
}
