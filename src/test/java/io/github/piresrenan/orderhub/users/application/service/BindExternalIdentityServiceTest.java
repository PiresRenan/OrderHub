package io.github.piresrenan.orderhub.users.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.piresrenan.orderhub.users.application.port.in.BindExternalIdentityCommand;
import io.github.piresrenan.orderhub.users.application.port.out.ExternalIdentityBindingRepository;
import io.github.piresrenan.orderhub.users.domain.model.ExternalIdentityBinding;

class BindExternalIdentityServiceTest {

    @Test
    void bindsExternalIdentityToInternalUser() {
        // Why: application callers need a framework-neutral orchestration boundary
        // for associating validated provider identity with an internal User.
        // Covers: domain construction, exact identity propagation and one repository
        // save.
        // Prevents: security adapters constructing or persisting bindings directly.

        var issuer = "https://issuer.example.test";
        var subject = "synthetic-subject-001";
        var userId = UUID.randomUUID();

        var repository = new RecordingExternalIdentityBindingRepository();

        var service = new BindExternalIdentityService(repository);

        var result = service.bind(
                new BindExternalIdentityCommand(
                        issuer,
                        subject,
                        userId));

        assertThat(result.issuer())
                .isEqualTo(issuer);

        assertThat(result.subject())
                .isEqualTo(subject);

        assertThat(result.userId())
                .isEqualTo(userId);

        assertThat(repository.savedBinding)
                .isSameAs(result);

        assertThat(repository.saveCount)
                .isEqualTo(1);
    }

    @Test
    void preservesExternalIdentityExactlyBeforePersistence() {
        // Why: application orchestration must not silently normalize identity values
        // after the domain has deliberately preserved them.
        // Covers: exact issuer/subject propagation through the bind use case.
        // Prevents: trim, lowercase or provider-specific transformations entering
        // through the application layer.

        var issuer = "https://Issuer.Example.test/Identity/";
        var subject = " Synthetic-Subject:Case-Sensitive ";

        var repository = new RecordingExternalIdentityBindingRepository();

        var service = new BindExternalIdentityService(repository);

        service.bind(
                new BindExternalIdentityCommand(
                        issuer,
                        subject,
                        UUID.randomUUID()));

        assertThat(repository.savedBinding.issuer())
                .isEqualTo(issuer);

        assertThat(repository.savedBinding.subject())
                .isEqualTo(subject);
    }

    @Test
    void rejectsBlankIssuerBeforeRepositoryAccess() {
        // Why: structurally unusable external identity must fail before crossing the
        // persistence boundary.
        // Covers: issuer invariant through application orchestration.
        // Prevents: invalid provider namespaces reaching infrastructure code.

        var repository = new RecordingExternalIdentityBindingRepository();

        var service = new BindExternalIdentityService(repository);

        assertThatThrownBy(() ->
                service.bind(
                        new BindExternalIdentityCommand(
                                "   ",
                                "synthetic-subject-001",
                                UUID.randomUUID())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("External identity issuer is required");

        assertThat(repository.saveCount)
                .isZero();
    }

    @Test
    void rejectsBlankSubjectBeforeRepositoryAccess() {
        // Why: a structurally unusable subject cannot establish external identity.
        // Covers: subject invariant through application orchestration.
        // Prevents: meaningless authentication identifiers reaching persistence.

        var repository = new RecordingExternalIdentityBindingRepository();

        var service = new BindExternalIdentityService(repository);

        assertThatThrownBy(() ->
                service.bind(
                        new BindExternalIdentityCommand(
                                "https://issuer.example.test",
                                "   ",
                                UUID.randomUUID())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("External identity subject is required");

        assertThat(repository.saveCount)
                .isZero();
    }

    @Test
    void rejectsMissingUserIdBeforeRepositoryAccess() {
        // Why: an external identity binding must target an internal User identity.
        // Covers: internal userId invariant through application orchestration.
        // Prevents: application services attempting to persist orphan bindings.

        var repository = new RecordingExternalIdentityBindingRepository();

        var service = new BindExternalIdentityService(repository);

        assertThatThrownBy(() ->
                service.bind(
                        new BindExternalIdentityCommand(
                                "https://issuer.example.test",
                                "synthetic-subject-001",
                                null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("External identity user id is required");

        assertThat(repository.saveCount)
                .isZero();
    }

    private static final class RecordingExternalIdentityBindingRepository
            implements ExternalIdentityBindingRepository {

        private ExternalIdentityBinding savedBinding;
        private int saveCount;

        /**
         * Records the binding supplied by the application service without
         * introducing persistence behavior into this unit test.
         *
         * @param binding external identity association requested for persistence
         * @return the same valid binding
         */
        @Override
        public ExternalIdentityBinding save(
                ExternalIdentityBinding binding) {

            this.savedBinding = binding;
            this.saveCount++;

            return binding;
        }

        /**
         * Satisfies the repository query contract outside this bind-focused test
         * scope.
         *
         * @param issuer exact external identity issuer
         * @param subject exact external identity subject
         * @return never reached by these tests
         */
        @Override
        public Optional<ExternalIdentityBinding> find(
                String issuer,
                String subject) {

            throw new UnsupportedOperationException(
                    "Find is outside this test scope");
        }
    }
}
