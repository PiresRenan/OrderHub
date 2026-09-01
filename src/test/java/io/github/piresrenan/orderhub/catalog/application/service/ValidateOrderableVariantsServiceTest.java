package io.github.piresrenan.orderhub.catalog.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.piresrenan.orderhub.catalog.application.port.in.orderability.CatalogOrderabilityRejectedException;
import io.github.piresrenan.orderhub.catalog.application.port.in.orderability.ValidateOrderableVariantsCommand;
import io.github.piresrenan.orderhub.catalog.application.port.out.CatalogOrderabilityRepository;

class ValidateOrderableVariantsServiceTest {

    private static final UUID TENANT_ID =
            UUID.fromString(
                    "10000000-0000-0000-0000-000000000001");

    private static final UUID VARIANT_1 =
            UUID.fromString(
                    "30000000-0000-0000-0000-000000000001");

    private static final UUID VARIANT_2 =
            UUID.fromString(
                    "30000000-0000-0000-0000-000000000002");

    private static final UUID VARIANT_3 =
            UUID.fromString(
                    "30000000-0000-0000-0000-000000000003");

    private static final UUID PRODUCT_1 =
            UUID.fromString(
                    "20000000-0000-0000-0000-000000000001");

    private static final UUID PRODUCT_2 =
            UUID.fromString(
                    "20000000-0000-0000-0000-000000000002");

    @Test
    void deduplicatesAndLocksVariantsThenProductsInCanonicalUuidOrder() {

        var repository =
                new RecordingRepository();

        repository.variantProducts.put(
                VARIANT_1,
                PRODUCT_1);

        repository.variantProducts.put(
                VARIANT_2,
                PRODUCT_1);

        repository.variantProducts.put(
                VARIANT_3,
                PRODUCT_2);

        repository.activeProducts.addAll(
                Set.of(
                        PRODUCT_1,
                        PRODUCT_2));

        var service =
                new ValidateOrderableVariantsService(
                        repository);

        service.validate(
                new ValidateOrderableVariantsCommand(
                        TENANT_ID,
                        List.of(
                                VARIANT_3,
                                VARIANT_2,
                                VARIANT_1,
                                VARIANT_2)));

        assertThat(repository.variantLockOrder)
                .containsExactly(
                        VARIANT_1,
                        VARIANT_2,
                        VARIANT_3);

        assertThat(repository.productLockOrder)
                .containsExactly(
                        PRODUCT_1,
                        PRODUCT_2);
    }

    @Test
    void missingOrNonActiveVariantFailsClosedBeforeProductLocking() {

        var repository =
                new RecordingRepository();

        var service =
                new ValidateOrderableVariantsService(
                        repository);

        assertThatThrownBy(() ->
                service.validate(
                        new ValidateOrderableVariantsCommand(
                                TENANT_ID,
                                List.of(
                                        VARIANT_1))))
                .isInstanceOf(
                        CatalogOrderabilityRejectedException.class)
                .hasMessage(
                        "One or more Order items are unavailable.");

        assertThat(repository.variantLockOrder)
                .containsExactly(
                        VARIANT_1);

        assertThat(repository.productLockOrder)
                .isEmpty();
    }

    @Test
    void nonActiveOwningProductFailsClosed() {

        var repository =
                new RecordingRepository();

        repository.variantProducts.put(
                VARIANT_1,
                PRODUCT_1);

        var service =
                new ValidateOrderableVariantsService(
                        repository);

        assertThatThrownBy(() ->
                service.validate(
                        new ValidateOrderableVariantsCommand(
                                TENANT_ID,
                                List.of(
                                        VARIANT_1))))
                .isInstanceOf(
                        CatalogOrderabilityRejectedException.class);

        assertThat(repository.variantLockOrder)
                .containsExactly(
                        VARIANT_1);

        assertThat(repository.productLockOrder)
                .containsExactly(
                        PRODUCT_1);
    }

    private static final class RecordingRepository
            implements CatalogOrderabilityRepository {

        private final Map<UUID, UUID> variantProducts =
                new HashMap<>();

        private final Set<UUID> activeProducts =
                new HashSet<>();

        private final List<UUID> variantLockOrder =
                new ArrayList<>();

        private final List<UUID> productLockOrder =
                new ArrayList<>();

        @Override
        public Optional<UUID> lockActiveVariantProductId(
                UUID tenantId,
                UUID variantId) {

            assertThat(tenantId)
                    .isEqualTo(
                            TENANT_ID);

            variantLockOrder.add(
                    variantId);

            return Optional.ofNullable(
                    variantProducts.get(
                            variantId));
        }

        @Override
        public boolean lockActiveProduct(
                UUID tenantId,
                UUID productId) {

            assertThat(tenantId)
                    .isEqualTo(
                            TENANT_ID);

            productLockOrder.add(
                    productId);

            return activeProducts.contains(
                    productId);
        }
    }
}
