package io.github.piresrenan.orderhub.users.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.piresrenan.orderhub.users.application.port.in.ResolvedUserIdentity;
import io.github.piresrenan.orderhub.users.application.port.in.ResolveExternalIdentityQuery;
import io.github.piresrenan.orderhub.users.application.port.out.ExternalIdentityBindingRepository;
import io.github.piresrenan.orderhub.users.domain.model.ExternalIdentityBinding;

class ResolveExternalIdentityServiceTest {

    @Test
    void resolvesExternalIdentityToInternalUserId() {
        // Why: authentication infrastructure ultimately needs the stable internal
        // User identity rather than provider-specific identity data.
        // Covers: exact identity lookup and projection to internal userId.
        // Prevents: JWT/provider identity leaking beyond the Users application
        // boundary.

        var issuer = "https://issuer.example.test";
        var subject = "synthetic-subject-001";
        var userId = UUID.randomUUID();

        var binding = ExternalIdentityBinding.rehydrate(
                issuer,
                subject,
                userId);

        var repository = new RecordingExternalIdentityBindingRepository(
                Optional.of(binding));

        var service = new ResolveExternalIdentityService(repository);

        var result = service.resolve(
                new ResolveExternalIdentityQuery(
                        issuer,
                        subject));

        assertThat(result)
                .contains(new ResolvedUserIdentity(userId));

        assertThat(repository.receivedIssuer)
                .isEqualTo(issuer);

        assertThat(repository.receivedSubject)
                .isEqualTo(subject);

        assertThat(repository.findCount)
                .isEqualTo(1);
    }

    @Test
    void returnsEmptyWhenExternalIdentityIsUnknown() {
        // Why: an unknown binding is a normal Users query result; authentication
        // semantics belong to the later security boundary.
        // Covers: absence propagation as Optional.empty().
        // Prevents: Users becoming coupled to HTTP 401 or Spring Security failures.

        var repository = new RecordingExternalIdentityBindingRepository(
                Optional.empty());

        var service = new ResolveExternalIdentityService(repository);

        var result = service.resolve(
                new ResolveExternalIdentityQuery(
                        "https://issuer.example.test",
                        "synthetic-unknown-subject"));

        assertThat(result)
                .isEmpty();

        assertThat(repository.findCount)
                .isEqualTo(1);
    }

    @Test
    void preservesLookupIdentityExactly() {
        // Why: identity resolution must use exactly the issuer and subject that
        // survived validated authentication.
        // Covers: exact query propagation without normalization.
        // Prevents: application-layer trim/lowercase changing lookup identity.

        var issuer = "https://Issuer.Example.test/Identity/";
        var subject = " Synthetic-Subject:Case-Sensitive ";

        var repository = new RecordingExternalIdentityBindingRepository(
                Optional.empty());

        var service = new ResolveExternalIdentityService(repository);

        service.resolve(
                new ResolveExternalIdentityQuery(
                        issuer,
                        subject));

        assertThat(repository.receivedIssuer)
                .isEqualTo(issuer);

        assertThat(repository.receivedSubject)
                .isEqualTo(subject);
    }

    @Test
    void rejectsMissingIssuerBeforeRepositoryAccess() {
        // Why: subject alone does not identify an external principal namespace.
        // Covers: required issuer at the resolution input boundary.
        // Prevents: partial external identity lookup reaching persistence.

        var repository = new RecordingExternalIdentityBindingRepository(
                Optional.empty());

        var service = new ResolveExternalIdentityService(repository);

        assertThatThrownBy(() -> service.resolve(
                new ResolveExternalIdentityQuery(
                        null,
                        "synthetic-subject-001")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("External identity issuer is required");

        assertThat(repository.findCount)
                .isZero();
    }

    @Test
    void rejectsBlankIssuerBeforeRepositoryAccess() {
        // Why: a blank issuer cannot represent the trusted authority namespace.
        // Covers: structurally unusable issuer rejection during resolution.
        // Prevents: meaningless issuer lookup reaching persistence.

        var repository = new RecordingExternalIdentityBindingRepository(
                Optional.empty());

        var service = new ResolveExternalIdentityService(repository);

        assertThatThrownBy(() -> service.resolve(
                new ResolveExternalIdentityQuery(
                        "   ",
                        "synthetic-subject-001")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("External identity issuer is required");

        assertThat(repository.findCount)
                .isZero();
    }

    @Test
    void rejectsMissingSubjectBeforeRepositoryAccess() {
        // Why: issuer without subject cannot identify one authenticated principal.
        // Covers: required subject at the resolution input boundary.
        // Prevents: incomplete external identity queries reaching persistence.

        var repository = new RecordingExternalIdentityBindingRepository(
                Optional.empty());

        var service = new ResolveExternalIdentityService(repository);

        assertThatThrownBy(() -> service.resolve(
                new ResolveExternalIdentityQuery(
                        "https://issuer.example.test",
                        null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("External identity subject is required");

        assertThat(repository.findCount)
                .isZero();
    }

    @Test
    void rejectsBlankSubjectBeforeRepositoryAccess() {
        // Why: a blank subject cannot identify the external principal.
        // Covers: structurally unusable subject rejection during resolution.
        // Prevents: blank authentication identity reaching persistence lookup.

        var repository = new RecordingExternalIdentityBindingRepository(
                Optional.empty());

        var service = new ResolveExternalIdentityService(repository);

        assertThatThrownBy(() -> service.resolve(
                new ResolveExternalIdentityQuery(
                        "https://issuer.example.test",
                        "   ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("External identity subject is required");

        assertThat(repository.findCount)
                .isZero();
    }

    private static final class RecordingExternalIdentityBindingRepository
            implements ExternalIdentityBindingRepository {

        private final Optional<ExternalIdentityBinding> result;

        private String receivedIssuer;
        private String receivedSubject;
        private int findCount;

        /**
         * Creates a focused repository double exposing one configured resolution
         * result.
         *
         * @param result external identity lookup result
         */
        private RecordingExternalIdentityBindingRepository(
                Optional<ExternalIdentityBinding> result) {

            this.result = result;
        }

        /**
         * Satisfies the repository write contract outside this resolution-focused
         * test scope.
         *
         * @param binding external identity binding requested for persistence
         * @return never reached by these tests
         */
        @Override
        public ExternalIdentityBinding save(
                ExternalIdentityBinding binding) {

            throw new UnsupportedOperationException(
                    "Save is outside this test scope");
        }

        /**
         * Records the exact external identity supplied for resolution.
         *
         * @param issuer  exact external identity issuer
         * @param subject exact external identity subject
         * @return configured lookup result
         */
        @Override
        public Optional<ExternalIdentityBinding> find(
                String issuer,
                String subject) {

            this.receivedIssuer = issuer;
            this.receivedSubject = subject;
            this.findCount++;

            return result;
        }
    }
}
