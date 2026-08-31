package io.github.piresrenan.orderhub.users.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class ExternalIdentityBindingTest {

    @Test
    void createsBindingBetweenExternalIdentityAndInternalUser() {
        // Why: an externally authenticated identity needs a durable association
        // with the authentication-neutral internal User identity.
        // Covers: valid ExternalIdentityBinding creation.
        // Prevents: credential-provider identity being embedded into User itself.

        var userId = UUID.randomUUID();

        var binding = ExternalIdentityBinding.create(
                "https://issuer.example.test",
                "synthetic-subject-001",
                userId);

        assertThat(binding.issuer())
                .isEqualTo("https://issuer.example.test");

        assertThat(binding.subject())
                .isEqualTo("synthetic-subject-001");

        assertThat(binding.userId())
                .isEqualTo(userId);
    }

    @Test
    void preservesExternalIdentityExactly() {
        // Why: issuer and subject are provider identity values rather than display
        // values and changing them could resolve a different external identity.
        // Covers: exact preservation of case, punctuation and surrounding
        // non-empty whitespace.
        // Prevents: accidental trim, lowercase or provider-specific normalization.

        var issuer = "https://Issuer.Example.test/Identity/";
        var subject = " Synthetic-Subject:Case-Sensitive ";
        var userId = UUID.randomUUID();

        var binding = ExternalIdentityBinding.create(
                issuer,
                subject,
                userId);

        assertThat(binding.issuer())
                .isEqualTo(issuer);

        assertThat(binding.subject())
                .isEqualTo(subject);
    }

    @Test
    void rejectsMissingIssuer() {
        // Why: subject alone is not globally meaningful without its issuing
        // authority.
        // Covers: required issuer invariant.
        // Prevents: ambiguous external identities entering the domain.

        assertThatThrownBy(() ->
                ExternalIdentityBinding.create(
                        null,
                        "synthetic-subject-001",
                        UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("External identity issuer is required");
    }

    @Test
    void rejectsBlankIssuer() {
        // Why: a blank issuer cannot identify the trusted authority that owns the
        // external subject namespace.
        // Covers: structurally unusable issuer rejection.
        // Prevents: persistence of meaningless issuer namespaces.

        assertThatThrownBy(() ->
                ExternalIdentityBinding.create(
                        "   ",
                        "synthetic-subject-001",
                        UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("External identity issuer is required");
    }

    @Test
    void rejectsMissingSubject() {
        // Why: an external identity cannot be resolved without the provider's
        // subject identifier.
        // Covers: required subject invariant.
        // Prevents: bindings that cannot correspond to an authenticated subject.

        assertThatThrownBy(() ->
                ExternalIdentityBinding.create(
                        "https://issuer.example.test",
                        null,
                        UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("External identity subject is required");
    }

    @Test
    void rejectsBlankSubject() {
        // Why: a blank subject is structurally incapable of establishing an
        // OrderHub external identity.
        // Covers: structurally unusable subject rejection.
        // Prevents: blank authenticated identities reaching persistence lookup.

        assertThatThrownBy(() ->
                ExternalIdentityBinding.create(
                        "https://issuer.example.test",
                        "   ",
                        UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("External identity subject is required");
    }

    @Test
    void rejectsMissingUserId() {
        // Why: a binding must terminate in an existing conceptual internal User
        // identity even before database referential integrity is introduced.
        // Covers: required internal userId invariant.
        // Prevents: external identities detached from OrderHub identity.

        assertThatThrownBy(() ->
                ExternalIdentityBinding.create(
                        "https://issuer.example.test",
                        "synthetic-subject-001",
                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("External identity user id is required");
    }

    @Test
    void rehydratesPersistedBinding() {
        // Why: persistence adapters need an explicit reconstruction path that
        // reapplies the same identity invariants as new bindings.
        // Covers: ExternalIdentityBinding rehydration.
        // Prevents: persistence reconstruction bypassing domain validation.

        var issuer = "https://issuer.example.test";
        var subject = "synthetic-subject-001";
        var userId = UUID.randomUUID();

        var binding = ExternalIdentityBinding.rehydrate(
                issuer,
                subject,
                userId);

        assertThat(binding.issuer())
                .isEqualTo(issuer);

        assertThat(binding.subject())
                .isEqualTo(subject);

        assertThat(binding.userId())
                .isEqualTo(userId);
    }

    @Test
    void rehydrationRejectsMissingIssuer() {
        // Why: persisted state must not weaken the external identity invariant.
        // Covers: issuer validation during reconstruction.
        // Prevents: corrupted persistence state entering the domain.

        assertThatThrownBy(() ->
                ExternalIdentityBinding.rehydrate(
                        null,
                        "synthetic-subject-001",
                        UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("External identity issuer is required");
    }

    @Test
    void rehydrationRejectsMissingSubject() {
        // Why: reconstructed state still requires the provider subject that
        // identifies the external principal.
        // Covers: subject validation during reconstruction.
        // Prevents: persisted bindings without usable external identity.

        assertThatThrownBy(() ->
                ExternalIdentityBinding.rehydrate(
                        "https://issuer.example.test",
                        null,
                        UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("External identity subject is required");
    }

    @Test
    void rehydrationRejectsMissingUserId() {
        // Why: persisted external identity must always resolve toward an internal
        // User identity.
        // Covers: userId validation during reconstruction.
        // Prevents: orphan external identity state entering the application.

        assertThatThrownBy(() ->
                ExternalIdentityBinding.rehydrate(
                        "https://issuer.example.test",
                        "synthetic-subject-001",
                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("External identity user id is required");
    }
}
