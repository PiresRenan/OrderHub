package io.github.piresrenan.orderhub.catalog.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.piresrenan.orderhub.catalog.application.port.in.administration.*;
import io.github.piresrenan.orderhub.catalog.application.port.out.*;
import io.github.piresrenan.orderhub.catalog.domain.model.*;

/** Proves authorization ordering and explicit lifecycle semantics before JDBC. */
class CatalogAdministrationServiceTest {
    private final UUID tenant = UUID.randomUUID();
    private final UUID id = UUID.randomUUID();
    private final CatalogAdminContext actor = new CatalogAdminContext(UUID.randomUUID(), tenant, "catalog-test");
    private final CatalogAdminAuthorizer authorizer = mock(CatalogAdminAuthorizer.class);
    private final CatalogAdministrationRepository repository = mock(CatalogAdministrationRepository.class);
    private CatalogAdministrationService service;

    @BeforeEach
    void setUp() {
        service = new CatalogAdministrationService(authorizer, repository, new CatalogAdminTransactionExecutor() {
            public <T> T execute(java.util.function.Supplier<T> action) { return action.get(); }
        }, Clock.fixed(Instant.parse("2026-09-07T12:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void deniedActorCannotLookupOrCreateProduct() {
        doThrow(new CatalogAdminDeniedException()).when(authorizer).require(actor, CatalogAdminPermission.MANAGE);
        assertThatThrownBy(() -> service.createProduct(actor, id, metadata("Name")))
                .isInstanceOf(CatalogAdminDeniedException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void metadataMutationPreservesLifecycleAndChecksRevision() {
        var stored = Product.rehydrate(id, tenant, "Old", "old", null, null, List.of(), ProductStatus.ACTIVE);
        when(repository.product(tenant, id, true)).thenReturn(java.util.Optional.of(new CatalogRevision<>(stored, 3)));
        var result = service.updateProduct(actor, id, 3, metadata("New"));
        assertThat(result.value().status()).isEqualTo(ProductStatus.ACTIVE);
        assertThat(result.value().name()).isEqualTo("New");
        assertThat(result.revision()).isEqualTo(4);
        var ordering = inOrder(authorizer, repository);
        ordering.verify(authorizer).require(actor, CatalogAdminPermission.MANAGE);
        ordering.verify(repository).product(tenant, id, true);
        ordering.verify(repository).updateProduct(any(), eq(3L));
        ordering.verify(repository).appendAudit(any());
    }

    @Test
    void staleMetadataCannotRestoreOldSnapshot() {
        var stored = Product.rehydrate(id, tenant, "Old", "old", null, null, List.of(), ProductStatus.ARCHIVED);
        when(repository.product(tenant, id, true)).thenReturn(java.util.Optional.of(new CatalogRevision<>(stored, 4)));
        assertThatThrownBy(() -> service.updateProduct(actor, id, 3, metadata("Stale")))
                .isInstanceOf(CatalogAdminConflictException.class);
        verify(repository, never()).updateProduct(any(), anyLong());
        verify(repository, never()).appendAudit(any());
    }

    @Test
    void activationLocksVariantWitnessBeforeProductAndRejectsWithoutOne() {
        when(repository.lockActivationWitness(tenant, id)).thenReturn(java.util.Optional.empty());
        assertThatThrownBy(() -> service.activateProduct(actor, id, 1))
                .isInstanceOf(CatalogAdminConflictException.class);
        verify(repository, never()).product(any(), any(), anyBoolean());
    }

    @Test
    void draftCreationAppendsEvidenceWithActorAndRevision() {
        var result = service.createProduct(actor, id, metadata("Name"));
        assertThat(result.value().status()).isEqualTo(ProductStatus.DRAFT);
        assertThat(result.revision()).isEqualTo(1);
        verify(repository).insertProduct(any());
        verify(repository).appendAudit(argThat(e -> e.actorId().equals(actor.userId())
                && e.tenantId().equals(tenant) && e.beforeRevision() == 0 && e.afterRevision() == 1));
    }

    private CatalogProductMetadata metadata(String name) {
        return new CatalogProductMetadata(name, "test-product", null, null);
    }
}
