package io.github.piresrenan.orderhub.catalog.application.service;

import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;

import io.github.piresrenan.orderhub.catalog.application.port.in.orderability.CatalogOrderabilityRejectedException;
import io.github.piresrenan.orderhub.catalog.application.port.in.orderability.CatalogOrderabilityTechnicalException;
import io.github.piresrenan.orderhub.catalog.application.port.in.orderability.ValidateOrderableVariantsCommand;
import io.github.piresrenan.orderhub.catalog.application.port.in.orderability.ValidateOrderableVariantsUseCase;
import io.github.piresrenan.orderhub.catalog.application.port.out.CatalogOrderabilityRepository;
import io.github.piresrenan.orderhub.catalog.application.port.out.CatalogPersistenceException;

/**
 * Validates Catalog commercial eligibility in deterministic lock order.
 */
public final class ValidateOrderableVariantsService
        implements ValidateOrderableVariantsUseCase {

    private final CatalogOrderabilityRepository repository;

    public ValidateOrderableVariantsService(
            CatalogOrderabilityRepository repository) {

        this.repository =
                Objects.requireNonNull(
                        repository,
                        "repository");
    }

    @Override
    public void validate(
            ValidateOrderableVariantsCommand command) {

        Objects.requireNonNull(
                command,
                "command");

        /*
         * UUID natural ordering is deterministic across JVM instances.
         * TreeSet also removes duplicate Order-line Variant identities before
         * any database lock is acquired.
         */
        var orderedVariantIds =
                new TreeSet<UUID>(
                        command.variantIds());

        var orderedProductIds =
                new TreeSet<UUID>();

        try {

            for (var variantId : orderedVariantIds) {

                var productId =
                        repository
                                .lockActiveVariantProductId(
                                        command.tenantId(),
                                        variantId)
                                .orElseThrow(
                                        CatalogOrderabilityRejectedException::new);

                orderedProductIds.add(
                        productId);
            }

            for (var productId : orderedProductIds) {

                if (
                    !repository.lockActiveProduct(
                            command.tenantId(),
                            productId)
                ) {

                    throw new CatalogOrderabilityRejectedException();
                }
            }
        } catch (CatalogPersistenceException exception) {

            throw new CatalogOrderabilityTechnicalException(
                    exception);
        }
    }
}
