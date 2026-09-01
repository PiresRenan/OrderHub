package io.github.piresrenan.orderhub.catalog.domain.model;

/**
 * Commercial lifecycle of a Catalog Product.
 *
 * <p>
 * OH-011 establishes the states but does not yet expose lifecycle transitions.
 * Products are created as DRAFT. Activation requirements will be enforced when
 * the activation use case exists and can verify related sellable Variants.
 * </p>
 */
public enum ProductStatus {

    /**
     * Product exists in the Catalog but is not yet sellable.
     */
    DRAFT,

    /**
     * Product is commercially active.
     */
    ACTIVE,

    /**
     * Product is retained historically but removed from normal commercial use.
     */
    ARCHIVED
}