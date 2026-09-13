package io.github.piresrenan.orderhub.catalog.adapter.in.web;

import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import io.github.piresrenan.orderhub.security.application.model.TrustedActorContext;
import io.github.piresrenan.orderhub.catalog.application.port.in.administration.*;
import io.github.piresrenan.orderhub.catalog.application.service.*;
import io.github.piresrenan.orderhub.catalog.domain.model.*;

/** Thin owner HTTP adapter: trusted actor, bounded transport and explicit application commands. */
@Tag(name = "Catalog")
@RestController
@RequestMapping(value="/catalog",produces=MediaType.APPLICATION_JSON_VALUE)
public final class CatalogAdministrationController {
    private final CatalogAdministrationService products;
    private final CatalogCategoryAdministrationService categories;
    private final CatalogPricingAdministrationService prices;
    private final CatalogAdministrationReadService reads;
    /** Composes only the Catalog owner command and bounded read services. */
    public CatalogAdministrationController(CatalogAdministrationService products,CatalogCategoryAdministrationService categories,
            CatalogPricingAdministrationService prices,CatalogAdministrationReadService reads) {
        this.products=products; this.categories=categories; this.prices=prices; this.reads=reads;
    }
    /** Create a draft Product; policy and state changes remain in the owner application boundary. */
    @Operation(operationId = "catalogCreateProduct", summary = "Create a draft Product",
            description = "Requires current Tenant Staff authority and CATALOG_MANAGE; bearer roles and upper administrative grants confer no Tenant permission. Caller supplies immutable resource UUID. Returns Location for the created Product. Duplicate identity or slug conflicts; no durable request replay key exists.",
            parameters = @Parameter(name = "X-Tenant-Id", in = ParameterIn.HEADER, required = true,
                    description = "Requested Tenant selector; active membership and active Tenant are verified against the bearer identity.",
                    schema = @Schema(type = "string", format = "uuid")))
    @ApiResponse(responseCode = "201", headers = @Header(name = "Location", description = "Relative URI of the created resource", schema = @Schema(type = "string", format = "uri-reference")), description = "Create a draft Product succeeded", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ProductView.class)))
    @ApiResponse(responseCode = "409", description = "Current lifecycle, revision, hierarchy or uniqueness conflicts with the requested change", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    @PostMapping(value="/products",consumes=MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ProductView> createProduct(@Parameter(hidden = true) TrustedActorContext actor,@Valid @RequestBody ProductCreate request) {
        var result=ProductView.of(products.createProduct(context(actor),request.id(),request.metadata()));
        return ResponseEntity.created(URI.create("/catalog/products/"+result.id())).body(result);
    }
    /** Replace Product metadata; policy and state changes remain in the owner application boundary. */
    @Operation(operationId = "catalogUpdateProduct", summary = "Replace Product metadata",
            description = "Requires current Tenant Staff authority and CATALOG_MANAGE; bearer roles and upper administrative grants confer no Tenant permission. Uses the observed resource revision; stale or incompatible changes return 409. Read and reconcile after an ambiguous response before retrying. Does not change lifecycle or Category assignments.",
            parameters = @Parameter(name = "X-Tenant-Id", in = ParameterIn.HEADER, required = true,
                    description = "Requested Tenant selector; active membership and active Tenant are verified against the bearer identity.",
                    schema = @Schema(type = "string", format = "uuid")))
    @ApiResponse(responseCode = "200", description = "Replace Product metadata succeeded", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ProductView.class)))
    @ApiResponse(responseCode = "409", description = "Current lifecycle, revision, hierarchy or uniqueness conflicts with the requested change", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Authorized lookup found no available resource in this Tenant", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    @PutMapping(value="/products/{id}/metadata",consumes=MediaType.APPLICATION_JSON_VALUE)
    ProductView updateProduct(@Parameter(hidden = true) TrustedActorContext actor,@Parameter(description = "Tenant-owned resource UUID selector") @PathVariable UUID id,@Valid @RequestBody ProductUpdate request) {
        return ProductView.of(products.updateProduct(context(actor),id,integer(request.expectedRevision()),request.metadata()));
    }
    /** Activate a Product; policy and state changes remain in the owner application boundary. */
    @Operation(operationId = "catalogActivateProduct", summary = "Activate a Product",
            description = "Requires current Tenant Staff authority and CATALOG_MANAGE; bearer roles and upper administrative grants confer no Tenant permission. Uses the observed resource revision; stale or incompatible changes return 409. Read and reconcile after an ambiguous response before retrying. Requires an active same-Product Variant; coordinates with Order eligibility.",
            parameters = @Parameter(name = "X-Tenant-Id", in = ParameterIn.HEADER, required = true,
                    description = "Requested Tenant selector; active membership and active Tenant are verified against the bearer identity.",
                    schema = @Schema(type = "string", format = "uuid")))
    @ApiResponse(responseCode = "200", description = "Activate a Product succeeded", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ProductView.class)))
    @ApiResponse(responseCode = "409", description = "Current lifecycle, revision, hierarchy or uniqueness conflicts with the requested change", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Authorized lookup found no available resource in this Tenant", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    @PostMapping(value="/products/{id}/activate",consumes=MediaType.APPLICATION_JSON_VALUE)
    ProductView activateProduct(@Parameter(hidden = true) TrustedActorContext actor,@Parameter(description = "Tenant-owned resource UUID selector") @PathVariable UUID id,@Valid @RequestBody RevisionRequest request) {
        return ProductView.of(products.activateProduct(context(actor),id,integer(request.expectedRevision())));
    }
    /** Archive a Product; policy and state changes remain in the owner application boundary. */
    @Operation(operationId = "catalogArchiveProduct", summary = "Archive a Product",
            description = "Requires current Tenant Staff authority and CATALOG_MANAGE; bearer roles and upper administrative grants confer no Tenant permission. Uses the observed resource revision; stale or incompatible changes return 409. Read and reconcile after an ambiguous response before retrying. Preserves identity and existing Order history.",
            parameters = @Parameter(name = "X-Tenant-Id", in = ParameterIn.HEADER, required = true,
                    description = "Requested Tenant selector; active membership and active Tenant are verified against the bearer identity.",
                    schema = @Schema(type = "string", format = "uuid")))
    @ApiResponse(responseCode = "200", description = "Archive a Product succeeded", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ProductView.class)))
    @ApiResponse(responseCode = "409", description = "Current lifecycle, revision, hierarchy or uniqueness conflicts with the requested change", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Authorized lookup found no available resource in this Tenant", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    @PostMapping(value="/products/{id}/archive",consumes=MediaType.APPLICATION_JSON_VALUE)
    ProductView archiveProduct(@Parameter(hidden = true) TrustedActorContext actor,@Parameter(description = "Tenant-owned resource UUID selector") @PathVariable UUID id,@Valid @RequestBody RevisionRequest request) {
        return ProductView.of(products.archiveProduct(context(actor),id,integer(request.expectedRevision())));
    }
    /** Replace Product Category assignments; policy and state changes remain in the owner application boundary. */
    @Operation(operationId = "catalogAssignCategories", summary = "Replace Product Category assignments",
            description = "Requires current Tenant Staff authority and CATALOG_MANAGE; bearer roles and upper administrative grants confer no Tenant permission. Uses the observed resource revision; stale or incompatible changes return 409. Read and reconcile after an ambiguous response before retrying. At most 100 distinct same-Tenant Category IDs; the empty list removes assignments.",
            parameters = @Parameter(name = "X-Tenant-Id", in = ParameterIn.HEADER, required = true,
                    description = "Requested Tenant selector; active membership and active Tenant are verified against the bearer identity.",
                    schema = @Schema(type = "string", format = "uuid")))
    @ApiResponse(responseCode = "200", description = "Replace Product Category assignments succeeded", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ProductView.class)))
    @ApiResponse(responseCode = "409", description = "Current lifecycle, revision, hierarchy or uniqueness conflicts with the requested change", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Authorized lookup found no available resource in this Tenant", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    @PutMapping(value="/products/{id}/categories",consumes=MediaType.APPLICATION_JSON_VALUE)
    ProductView assignCategories(@Parameter(hidden = true) TrustedActorContext actor,@Parameter(description = "Tenant-owned resource UUID selector") @PathVariable UUID id,@Valid @RequestBody AssignmentsRequest request) {
        return ProductView.of(products.assignCategories(context(actor),id,integer(request.expectedRevision()),request.categoryIds()));
    }
    /** Read a Product; policy and state changes remain in the owner application boundary. */
    @Operation(operationId = "catalogProduct", summary = "Read a Product",
            description = "Requires current Tenant Staff authority and CATALOG_VIEW; bearer roles and upper administrative grants confer no Tenant permission. Authorization precedes sensitive lookup; absent or foreign resources are equivalent 404 responses. ",
            parameters = @Parameter(name = "X-Tenant-Id", in = ParameterIn.HEADER, required = true,
                    description = "Requested Tenant selector; active membership and active Tenant are verified against the bearer identity.",
                    schema = @Schema(type = "string", format = "uuid")))
    @ApiResponse(responseCode = "200", description = "Read a Product succeeded", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ProductView.class)))
    @ApiResponse(responseCode = "404", description = "Authorized lookup found no available resource in this Tenant", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    @GetMapping("/products/{id}")
    ProductView product(@Parameter(hidden = true) TrustedActorContext actor,@Parameter(description = "Tenant-owned resource UUID selector") @PathVariable UUID id) { return ProductView.of(products.product(context(actor),id)); }
    /** List Products; policy and state changes remain in the owner application boundary. */
    @Operation(operationId = "catalogProducts", summary = "List Products",
            description = "Requires current Tenant Staff authority and CATALOG_VIEW; bearer roles and upper administrative grants confer no Tenant permission. Exclusive UUID cursor in PostgreSQL order, limit 1-100 (default 50). Returns a current-state array, not a frozen snapshot or chronological export. ",
            parameters = @Parameter(name = "X-Tenant-Id", in = ParameterIn.HEADER, required = true,
                    description = "Requested Tenant selector; active membership and active Tenant are verified against the bearer identity.",
                    schema = @Schema(type = "string", format = "uuid")))
    @ApiResponse(responseCode = "200", description = "List Products succeeded", content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = CatalogProductSummary.class))))
    @GetMapping("/products")
    List<CatalogProductSummary> products(@Parameter(hidden = true) TrustedActorContext actor,@Parameter(description = "Exclusive PostgreSQL UUID cursor; omitted for the first page", schema = @Schema(type = "string", format = "uuid")) @RequestParam(required=false) UUID afterId,@Parameter(description = "Maximum rows returned; current-state page without a total count", schema = @Schema(type = "integer", types = {"integer"}, format = "int32", minimum = "1", maximum = "100", defaultValue = "50")) @RequestParam(defaultValue="50") int limit) {
        return reads.products(context(actor),afterId,limit);
    }
    /** Create a draft Product Variant; policy and state changes remain in the owner application boundary. */
    @Operation(operationId = "catalogCreateVariant", summary = "Create a draft Product Variant",
            description = "Requires current Tenant Staff authority and CATALOG_MANAGE; bearer roles and upper administrative grants confer no Tenant permission. Caller supplies immutable Variant UUID; parent Product cannot be reassigned. Returns Location. Duplicate identity or SKU conflicts.",
            parameters = @Parameter(name = "X-Tenant-Id", in = ParameterIn.HEADER, required = true,
                    description = "Requested Tenant selector; active membership and active Tenant are verified against the bearer identity.",
                    schema = @Schema(type = "string", format = "uuid")))
    @ApiResponse(responseCode = "201", headers = @Header(name = "Location", description = "Relative URI of the created resource", schema = @Schema(type = "string", format = "uri-reference")), description = "Create a draft Product Variant succeeded", content = @Content(mediaType = "application/json", schema = @Schema(implementation = VariantView.class)))
    @ApiResponse(responseCode = "409", description = "Current lifecycle, revision, hierarchy or uniqueness conflicts with the requested change", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Authorized lookup found no available resource in this Tenant", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    @PostMapping(value="/products/{productId}/variants",consumes=MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<VariantView> createVariant(@Parameter(hidden = true) TrustedActorContext actor,@Parameter(description = "Owning Product UUID selector") @PathVariable UUID productId,@Valid @RequestBody VariantCreate request) {
        var result=VariantView.of(products.createVariant(context(actor),productId,request.id(),request.metadata()));
        return ResponseEntity.created(URI.create("/catalog/variants/"+result.id())).body(result);
    }
    /** List Variants of a Product; policy and state changes remain in the owner application boundary. */
    @Operation(operationId = "catalogVariants", summary = "List Variants of a Product",
            description = "Requires current Tenant Staff authority and CATALOG_VIEW; bearer roles and upper administrative grants confer no Tenant permission. Exclusive UUID cursor in PostgreSQL order, limit 1-100 (default 50). Returns a current-state array, not a frozen snapshot or chronological export. Authorization precedes sensitive lookup; absent or foreign resources are equivalent 404 responses. ",
            parameters = @Parameter(name = "X-Tenant-Id", in = ParameterIn.HEADER, required = true,
                    description = "Requested Tenant selector; active membership and active Tenant are verified against the bearer identity.",
                    schema = @Schema(type = "string", format = "uuid")))
    @ApiResponse(responseCode = "200", description = "List Variants of a Product succeeded", content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = CatalogVariantSummary.class))))
    @ApiResponse(responseCode = "404", description = "Authorized lookup found no available resource in this Tenant", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    @GetMapping("/products/{productId}/variants")
    List<CatalogVariantSummary> variants(@Parameter(hidden = true) TrustedActorContext actor,@Parameter(description = "Owning Product UUID selector") @PathVariable UUID productId,
            @Parameter(description = "Exclusive PostgreSQL UUID cursor; omitted for the first page", schema = @Schema(type = "string", format = "uuid")) @RequestParam(required=false) UUID afterId,@Parameter(description = "Maximum rows returned; current-state page without a total count", schema = @Schema(type = "integer", types = {"integer"}, format = "int32", minimum = "1", maximum = "100", defaultValue = "50")) @RequestParam(defaultValue="50") int limit) {
        return reads.variants(context(actor),productId,afterId,limit);
    }
    /** Read a Variant; policy and state changes remain in the owner application boundary. */
    @Operation(operationId = "catalogVariant", summary = "Read a Variant",
            description = "Requires current Tenant Staff authority and CATALOG_VIEW; bearer roles and upper administrative grants confer no Tenant permission. Authorization precedes sensitive lookup; absent or foreign resources are equivalent 404 responses. ",
            parameters = @Parameter(name = "X-Tenant-Id", in = ParameterIn.HEADER, required = true,
                    description = "Requested Tenant selector; active membership and active Tenant are verified against the bearer identity.",
                    schema = @Schema(type = "string", format = "uuid")))
    @ApiResponse(responseCode = "200", description = "Read a Variant succeeded", content = @Content(mediaType = "application/json", schema = @Schema(implementation = VariantView.class)))
    @ApiResponse(responseCode = "404", description = "Authorized lookup found no available resource in this Tenant", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    @GetMapping("/variants/{id}")
    VariantView variant(@Parameter(hidden = true) TrustedActorContext actor,@Parameter(description = "Tenant-owned resource UUID selector") @PathVariable UUID id) { return VariantView.of(products.variant(context(actor),id)); }
    /** Replace Variant metadata; policy and state changes remain in the owner application boundary. */
    @Operation(operationId = "catalogUpdateVariant", summary = "Replace Variant metadata",
            description = "Requires current Tenant Staff authority and CATALOG_MANAGE; bearer roles and upper administrative grants confer no Tenant permission. Uses the observed resource revision; stale or incompatible changes return 409. Read and reconcile after an ambiguous response before retrying. Does not alter lifecycle, immutable identity or parent Product.",
            parameters = @Parameter(name = "X-Tenant-Id", in = ParameterIn.HEADER, required = true,
                    description = "Requested Tenant selector; active membership and active Tenant are verified against the bearer identity.",
                    schema = @Schema(type = "string", format = "uuid")))
    @ApiResponse(responseCode = "200", description = "Replace Variant metadata succeeded", content = @Content(mediaType = "application/json", schema = @Schema(implementation = VariantView.class)))
    @ApiResponse(responseCode = "409", description = "Current lifecycle, revision, hierarchy or uniqueness conflicts with the requested change", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Authorized lookup found no available resource in this Tenant", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    @PutMapping(value="/variants/{id}/metadata",consumes=MediaType.APPLICATION_JSON_VALUE)
    VariantView updateVariant(@Parameter(hidden = true) TrustedActorContext actor,@Parameter(description = "Tenant-owned resource UUID selector") @PathVariable UUID id,@Valid @RequestBody VariantUpdate request) {
        return VariantView.of(products.updateVariant(context(actor),id,integer(request.expectedRevision()),request.metadata()));
    }
    /** Activate a Variant; policy and state changes remain in the owner application boundary. */
    @Operation(operationId = "catalogActivateVariant", summary = "Activate a Variant",
            description = "Requires current Tenant Staff authority and CATALOG_MANAGE; bearer roles and upper administrative grants confer no Tenant permission. Uses the observed resource revision; stale or incompatible changes return 409. Read and reconcile after an ambiguous response before retrying. Makes the Variant commercially eligible subject to its Product state.",
            parameters = @Parameter(name = "X-Tenant-Id", in = ParameterIn.HEADER, required = true,
                    description = "Requested Tenant selector; active membership and active Tenant are verified against the bearer identity.",
                    schema = @Schema(type = "string", format = "uuid")))
    @ApiResponse(responseCode = "200", description = "Activate a Variant succeeded", content = @Content(mediaType = "application/json", schema = @Schema(implementation = VariantView.class)))
    @ApiResponse(responseCode = "409", description = "Current lifecycle, revision, hierarchy or uniqueness conflicts with the requested change", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Authorized lookup found no available resource in this Tenant", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    @PostMapping(value="/variants/{id}/activate",consumes=MediaType.APPLICATION_JSON_VALUE)
    VariantView activateVariant(@Parameter(hidden = true) TrustedActorContext actor,@Parameter(description = "Tenant-owned resource UUID selector") @PathVariable UUID id,@Valid @RequestBody RevisionRequest request) {
        return VariantView.of(products.activateVariant(context(actor),id,integer(request.expectedRevision())));
    }
    /** Deactivate a Variant; policy and state changes remain in the owner application boundary. */
    @Operation(operationId = "catalogDeactivateVariant", summary = "Deactivate a Variant",
            description = "Requires current Tenant Staff authority and CATALOG_MANAGE; bearer roles and upper administrative grants confer no Tenant permission. Uses the observed resource revision; stale or incompatible changes return 409. Read and reconcile after an ambiguous response before retrying. Affects new Order eligibility, preserving existing commitments.",
            parameters = @Parameter(name = "X-Tenant-Id", in = ParameterIn.HEADER, required = true,
                    description = "Requested Tenant selector; active membership and active Tenant are verified against the bearer identity.",
                    schema = @Schema(type = "string", format = "uuid")))
    @ApiResponse(responseCode = "200", description = "Deactivate a Variant succeeded", content = @Content(mediaType = "application/json", schema = @Schema(implementation = VariantView.class)))
    @ApiResponse(responseCode = "409", description = "Current lifecycle, revision, hierarchy or uniqueness conflicts with the requested change", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Authorized lookup found no available resource in this Tenant", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    @PostMapping(value="/variants/{id}/deactivate",consumes=MediaType.APPLICATION_JSON_VALUE)
    VariantView deactivateVariant(@Parameter(hidden = true) TrustedActorContext actor,@Parameter(description = "Tenant-owned resource UUID selector") @PathVariable UUID id,@Valid @RequestBody RevisionRequest request) {
        return VariantView.of(products.deactivateVariant(context(actor),id,integer(request.expectedRevision())));
    }
    /** Archive a Variant; policy and state changes remain in the owner application boundary. */
    @Operation(operationId = "catalogArchiveVariant", summary = "Archive a Variant",
            description = "Requires current Tenant Staff authority and CATALOG_MANAGE; bearer roles and upper administrative grants confer no Tenant permission. Uses the observed resource revision; stale or incompatible changes return 409. Read and reconcile after an ambiguous response before retrying. Preserves historical identity and commitments.",
            parameters = @Parameter(name = "X-Tenant-Id", in = ParameterIn.HEADER, required = true,
                    description = "Requested Tenant selector; active membership and active Tenant are verified against the bearer identity.",
                    schema = @Schema(type = "string", format = "uuid")))
    @ApiResponse(responseCode = "200", description = "Archive a Variant succeeded", content = @Content(mediaType = "application/json", schema = @Schema(implementation = VariantView.class)))
    @ApiResponse(responseCode = "409", description = "Current lifecycle, revision, hierarchy or uniqueness conflicts with the requested change", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Authorized lookup found no available resource in this Tenant", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    @PostMapping(value="/variants/{id}/archive",consumes=MediaType.APPLICATION_JSON_VALUE)
    VariantView archiveVariant(@Parameter(hidden = true) TrustedActorContext actor,@Parameter(description = "Tenant-owned resource UUID selector") @PathVariable UUID id,@Valid @RequestBody RevisionRequest request) {
        return VariantView.of(products.archiveVariant(context(actor),id,integer(request.expectedRevision())));
    }
    /** Create a Category; policy and state changes remain in the owner application boundary. */
    @Operation(operationId = "catalogCreateCategory", summary = "Create a Category",
            description = "Requires current Tenant Staff authority and CATALOG_MANAGE; bearer roles and upper administrative grants confer no Tenant permission. Optional parent must belong to this Tenant. Null parent creates a root. Returns Location; hierarchy mutations serialize through the Tenant guard.",
            parameters = @Parameter(name = "X-Tenant-Id", in = ParameterIn.HEADER, required = true,
                    description = "Requested Tenant selector; active membership and active Tenant are verified against the bearer identity.",
                    schema = @Schema(type = "string", format = "uuid")))
    @ApiResponse(responseCode = "201", headers = @Header(name = "Location", description = "Relative URI of the created resource", schema = @Schema(type = "string", format = "uri-reference")), description = "Create a Category succeeded", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CategoryView.class)))
    @ApiResponse(responseCode = "409", description = "Current lifecycle, revision, hierarchy or uniqueness conflicts with the requested change", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Authorized lookup found no available resource in this Tenant", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    @PostMapping(value="/categories",consumes=MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<CategoryView> createCategory(@Parameter(hidden = true) TrustedActorContext actor,@Valid @RequestBody CategoryCreate request) {
        var result=CategoryView.of(categories.create(context(actor),request.id(),request.parentCategoryId(),request.name(),request.slug(),request.description()));
        return ResponseEntity.created(URI.create("/catalog/categories/"+result.id())).body(result);
    }
    /** Replace Category metadata; policy and state changes remain in the owner application boundary. */
    @Operation(operationId = "catalogUpdateCategory", summary = "Replace Category metadata",
            description = "Requires current Tenant Staff authority and CATALOG_MANAGE; bearer roles and upper administrative grants confer no Tenant permission. Uses the observed resource revision; stale or incompatible changes return 409. Read and reconcile after an ambiguous response before retrying. Keeps parent identity unchanged and uses the Tenant hierarchy guard.",
            parameters = @Parameter(name = "X-Tenant-Id", in = ParameterIn.HEADER, required = true,
                    description = "Requested Tenant selector; active membership and active Tenant are verified against the bearer identity.",
                    schema = @Schema(type = "string", format = "uuid")))
    @ApiResponse(responseCode = "200", description = "Replace Category metadata succeeded", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CategoryView.class)))
    @ApiResponse(responseCode = "409", description = "Current lifecycle, revision, hierarchy or uniqueness conflicts with the requested change", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Authorized lookup found no available resource in this Tenant", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    @PutMapping(value="/categories/{id}/metadata",consumes=MediaType.APPLICATION_JSON_VALUE)
    CategoryView updateCategory(@Parameter(hidden = true) TrustedActorContext actor,@Parameter(description = "Tenant-owned resource UUID selector") @PathVariable UUID id,@Valid @RequestBody CategoryUpdate request) {
        return CategoryView.of(categories.metadata(context(actor),id,integer(request.expectedRevision()),request.name(),request.slug(),request.description()));
    }
    /** Change a Category parent; policy and state changes remain in the owner application boundary. */
    @Operation(operationId = "catalogReparentCategory", summary = "Change a Category parent",
            description = "Requires current Tenant Staff authority and CATALOG_MANAGE; bearer roles and upper administrative grants confer no Tenant permission. Uses the observed resource revision; stale or incompatible changes return 409. Read and reconcile after an ambiguous response before retrying. Null parent makes a root; same-Tenant acyclic hierarchy is enforced under the Tenant hierarchy guard.",
            parameters = @Parameter(name = "X-Tenant-Id", in = ParameterIn.HEADER, required = true,
                    description = "Requested Tenant selector; active membership and active Tenant are verified against the bearer identity.",
                    schema = @Schema(type = "string", format = "uuid")))
    @ApiResponse(responseCode = "200", description = "Change a Category parent succeeded", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CategoryView.class)))
    @ApiResponse(responseCode = "409", description = "Current lifecycle, revision, hierarchy or uniqueness conflicts with the requested change", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Authorized lookup found no available resource in this Tenant", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    @PutMapping(value="/categories/{id}/parent",consumes=MediaType.APPLICATION_JSON_VALUE)
    CategoryView reparentCategory(@Parameter(hidden = true) TrustedActorContext actor,@Parameter(description = "Tenant-owned resource UUID selector") @PathVariable UUID id,@Valid @RequestBody ReparentRequest request) {
        return CategoryView.of(categories.reparent(context(actor),id,integer(request.expectedRevision()),request.parentCategoryId()));
    }
    /** Read a Category; policy and state changes remain in the owner application boundary. */
    @Operation(operationId = "catalogCategory", summary = "Read a Category",
            description = "Requires current Tenant Staff authority and CATALOG_VIEW; bearer roles and upper administrative grants confer no Tenant permission. Authorization precedes sensitive lookup; absent or foreign resources are equivalent 404 responses. Returns a flat node, not a recursive tree.",
            parameters = @Parameter(name = "X-Tenant-Id", in = ParameterIn.HEADER, required = true,
                    description = "Requested Tenant selector; active membership and active Tenant are verified against the bearer identity.",
                    schema = @Schema(type = "string", format = "uuid")))
    @ApiResponse(responseCode = "200", description = "Read a Category succeeded", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CategoryView.class)))
    @ApiResponse(responseCode = "404", description = "Authorized lookup found no available resource in this Tenant", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    @GetMapping("/categories/{id}")
    CategoryView category(@Parameter(hidden = true) TrustedActorContext actor,@Parameter(description = "Tenant-owned resource UUID selector") @PathVariable UUID id) { return CategoryView.of(categories.get(context(actor),id)); }
    /** List Category nodes; policy and state changes remain in the owner application boundary. */
    @Operation(operationId = "catalogCategories", summary = "List Category nodes",
            description = "Requires current Tenant Staff authority and CATALOG_VIEW; bearer roles and upper administrative grants confer no Tenant permission. Exclusive UUID cursor in PostgreSQL order, limit 1-100 (default 50). Returns a current-state array, not a frozen snapshot or chronological export. Flat nodes do not recursively embed children.",
            parameters = @Parameter(name = "X-Tenant-Id", in = ParameterIn.HEADER, required = true,
                    description = "Requested Tenant selector; active membership and active Tenant are verified against the bearer identity.",
                    schema = @Schema(type = "string", format = "uuid")))
    @ApiResponse(responseCode = "200", description = "List Category nodes succeeded", content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = CategoryView.class))))
    @GetMapping("/categories")
    List<CategoryView> categories(@Parameter(hidden = true) TrustedActorContext actor,@Parameter(description = "Exclusive PostgreSQL UUID cursor; omitted for the first page", schema = @Schema(type = "string", format = "uuid")) @RequestParam(required=false) UUID afterId,@Parameter(description = "Maximum rows returned; current-state page without a total count", schema = @Schema(type = "integer", types = {"integer"}, format = "int32", minimum = "1", maximum = "100", defaultValue = "50")) @RequestParam(defaultValue="50") int limit) {
        return reads.categories(context(actor),afterId,limit).stream().map(CategoryView::of).toList();
    }
    /** Set a Variant base price; policy and state changes remain in the owner application boundary. */
    @Operation(operationId = "catalogSetPrice", summary = "Set a Variant base price",
            description = "Requires current Tenant Staff authority and CATALOG_PRICE_MANAGE; bearer roles and upper administrative grants confer no Tenant permission. Exact nonnegative signed-64-bit minor units plus canonical recognized currency. expectedRevision 0 creates an absent price; subsequent writes require the observed positive revision. Stale writes conflict; read and reconcile after an ambiguous response.",
            parameters = @Parameter(name = "X-Tenant-Id", in = ParameterIn.HEADER, required = true,
                    description = "Requested Tenant selector; active membership and active Tenant are verified against the bearer identity.",
                    schema = @Schema(type = "string", format = "uuid")))
    @ApiResponse(responseCode = "200", description = "Set a Variant base price succeeded", content = @Content(mediaType = "application/json", schema = @Schema(implementation = PriceView.class)))
    @ApiResponse(responseCode = "409", description = "Current lifecycle, revision, hierarchy or uniqueness conflicts with the requested change", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Authorized lookup found no available resource in this Tenant", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    @PutMapping(value="/variants/{id}/prices/{currency}",consumes=MediaType.APPLICATION_JSON_VALUE)
    PriceView setPrice(@Parameter(hidden = true) TrustedActorContext actor,@Parameter(description = "Tenant-owned resource UUID selector") @PathVariable UUID id,@Parameter(description = "Recognized ISO 4217 currency in canonical uppercase", schema = @Schema(pattern = "^(?:[A-Z]{3})$", minLength = 3, maxLength = 3), example = "BRL") @PathVariable String currency,@Valid @RequestBody PriceRequest request) {
        return PriceView.of(prices.set(context(actor),id,currency,integer(request.expectedRevision()),integer(request.minorUnits())));
    }
    /** Read a Variant base price; policy and state changes remain in the owner application boundary. */
    @Operation(operationId = "catalogPrice", summary = "Read a Variant base price",
            description = "Requires current Tenant Staff authority and CATALOG_VIEW; bearer roles and upper administrative grants confer no Tenant permission. Authorization precedes sensitive lookup; absent or foreign resources are equivalent 404 responses. Returns exact minor units, currency and revision; no floating-point major-unit conversion.",
            parameters = @Parameter(name = "X-Tenant-Id", in = ParameterIn.HEADER, required = true,
                    description = "Requested Tenant selector; active membership and active Tenant are verified against the bearer identity.",
                    schema = @Schema(type = "string", format = "uuid")))
    @ApiResponse(responseCode = "200", description = "Read a Variant base price succeeded", content = @Content(mediaType = "application/json", schema = @Schema(implementation = PriceView.class)))
    @ApiResponse(responseCode = "404", description = "Authorized lookup found no available resource in this Tenant", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    @GetMapping("/variants/{id}/prices/{currency}")
    PriceView price(@Parameter(hidden = true) TrustedActorContext actor,@Parameter(description = "Tenant-owned resource UUID selector") @PathVariable UUID id,@Parameter(description = "Recognized ISO 4217 currency in canonical uppercase", schema = @Schema(pattern = "^(?:[A-Z]{3})$", minLength = 3, maxLength = 3), example = "BRL") @PathVariable String currency) { return PriceView.of(prices.get(context(actor),id,currency)); }

    /** Derives owner command attribution from trusted identity and a fresh server correlation UUID. */
    private static CatalogAdminContext context(TrustedActorContext actor) {
        return new CatalogAdminContext(actor.userId(),actor.tenantId(),UUID.randomUUID().toString());
    }
    /** Exact decimal parsing rejects fractional and overflowing numbers rather than coercing them. */
    private static long integer(BigDecimal number) {
        if(number==null) throw new IllegalArgumentException("Required integer");
        try { return number.longValueExact(); }
        catch(ArithmeticException exception) { throw new IllegalArgumentException("Invalid integer"); }
    }
    // Application metadata owns Unicode code-point limits; @Size would count UTF-16 units.
    @Schema(name = "CatalogProductCreate")
    record ProductCreate(
            @Schema(description = "Immutable client-selected resource UUID", requiredMode = Schema.RequiredMode.REQUIRED, type = "string", format = "uuid") @NotNull UUID id,
            @Schema(description = "Required nonblank name; surrounding Unicode whitespace is normalized", requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 160) @NotNull String name,
            @Schema(description = "Preserved URL-oriented identifier; no normalization", requiredMode = Schema.RequiredMode.REQUIRED, minLength = 2, maxLength = 256, pattern = "^(?:[A-Za-z0-9_-]+)$") @NotNull String slug,
            @Schema(description = "Optional description, at most 4000 Unicode code points", requiredMode = Schema.RequiredMode.NOT_REQUIRED, maxLength = 4000, nullable = true) String description,
            @Schema(description = "Optional nonblank brand; surrounding Unicode whitespace is normalized", maxLength = 120, nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED) String brand) {
        /** Projects authoring fields without lifecycle or ownership authority. */
        CatalogProductMetadata metadata() { return new CatalogProductMetadata(name,slug,description,brand); }
    }
    @Schema(name = "CatalogProductUpdate")
    record ProductUpdate(
            @Schema(description = "Previously observed positive revision", requiredMode = Schema.RequiredMode.REQUIRED, type = "integer", types = {"integer"}, format = "int64", minimum = "1", maximum = "9223372036854775806") @NotNull BigDecimal expectedRevision,
            @Schema(description = "Required nonblank name; surrounding Unicode whitespace is normalized", requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 160) @NotNull String name,
            @Schema(description = "Preserved URL-oriented identifier; no normalization", requiredMode = Schema.RequiredMode.REQUIRED, minLength = 2, maxLength = 256, pattern = "^(?:[A-Za-z0-9_-]+)$") @NotNull String slug,
            @Schema(description = "Optional description, at most 4000 Unicode code points", requiredMode = Schema.RequiredMode.NOT_REQUIRED, maxLength = 4000, nullable = true) String description,
            @Schema(description = "Optional nonblank brand; surrounding Unicode whitespace is normalized", maxLength = 120, nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED) String brand) {
        /** Projects authoring fields without lifecycle or ownership authority. */
        CatalogProductMetadata metadata() { return new CatalogProductMetadata(name,slug,description,brand); }
    }
    @Schema(name = "CatalogRevisionRequest")
    record RevisionRequest(
            @Schema(description = "Previously observed positive revision", type = "integer", types = {"integer"}, format = "int64", minimum = "1", maximum = "9223372036854775806", requiredMode = Schema.RequiredMode.REQUIRED) @NotNull BigDecimal expectedRevision) {}
    @Schema(name = "CatalogAssignmentsRequest")
    record AssignmentsRequest(
            @Schema(description = "Previously observed positive revision", requiredMode = Schema.RequiredMode.REQUIRED, type = "integer", types = {"integer"}, format = "int64", minimum = "1", maximum = "9223372036854775806") @NotNull BigDecimal expectedRevision,
            @ArraySchema(maxItems = 100, uniqueItems = true, schema = @Schema(type = "string", format = "uuid")) @NotNull @Size(max=100) List<@NotNull UUID> categoryIds) {}
    @Schema(name = "CatalogVariantCreate")
    record VariantCreate(
            @Schema(description = "Immutable client-selected resource UUID", requiredMode = Schema.RequiredMode.REQUIRED, type = "string", format = "uuid") @NotNull UUID id,
            @Schema(description = "Merchant SKU, at most 64 Unicode code points; no surrounding whitespace or control characters", requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 64) @NotNull @Size(max=160) String sku,
            @Schema(description = "Optional nonblank display name; surrounding Unicode whitespace is normalized", requiredMode = Schema.RequiredMode.NOT_REQUIRED, maxLength = 160, nullable = true) String displayName,
            @Schema(description = "Optional numeric GTIN of 8, 12, 13 or 14 digits with a valid GS1 check digit", requiredMode = Schema.RequiredMode.NOT_REQUIRED, pattern = "^(?:([0-9]{8}|[0-9]{12}|[0-9]{13}|[0-9]{14}))$", nullable = true) String gtin,
            @Schema(description = "Optional nonblank manufacturer part number; no surrounding whitespace or controls", requiredMode = Schema.RequiredMode.NOT_REQUIRED, maxLength = 70, nullable = true) String mpn,
            @ArraySchema(maxItems = 50, arraySchema = @Schema(description = "Distinct attribute keys: [A-Za-z][A-Za-z0-9._-]* up to 64 code points. Values are nonblank up to 256 code points, with no surrounding whitespace or controls.")) @NotNull @Size(max=50) List<@NotNull ProductVariantAttribute> attributes) {
        /** Projects Variant metadata while preserving separate parent and lifecycle commands. */
        CatalogVariantMetadata metadata() { return new CatalogVariantMetadata(sku,displayName,gtin,mpn,attributes); }
    }
    @Schema(name = "CatalogVariantUpdate")
    record VariantUpdate(
            @Schema(description = "Previously observed positive revision", requiredMode = Schema.RequiredMode.REQUIRED, type = "integer", types = {"integer"}, format = "int64", minimum = "1", maximum = "9223372036854775806") @NotNull BigDecimal expectedRevision,
            @Schema(description = "Merchant SKU, at most 64 Unicode code points; no surrounding whitespace or control characters", requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 64) @NotNull @Size(max=160) String sku,
            @Schema(description = "Optional nonblank display name; surrounding Unicode whitespace is normalized", requiredMode = Schema.RequiredMode.NOT_REQUIRED, maxLength = 160, nullable = true) String displayName,
            @Schema(description = "Optional numeric GTIN of 8, 12, 13 or 14 digits with a valid GS1 check digit", requiredMode = Schema.RequiredMode.NOT_REQUIRED, pattern = "^(?:([0-9]{8}|[0-9]{12}|[0-9]{13}|[0-9]{14}))$", nullable = true) String gtin,
            @Schema(description = "Optional nonblank manufacturer part number; no surrounding whitespace or controls", requiredMode = Schema.RequiredMode.NOT_REQUIRED, maxLength = 70, nullable = true) String mpn,
            @ArraySchema(maxItems = 50, arraySchema = @Schema(description = "Distinct attribute keys: [A-Za-z][A-Za-z0-9._-]* up to 64 code points. Values are nonblank up to 256 code points, with no surrounding whitespace or controls.")) @NotNull @Size(max=50) List<@NotNull ProductVariantAttribute> attributes) {
        /** Projects Variant metadata while preserving separate parent and lifecycle commands. */
        CatalogVariantMetadata metadata() { return new CatalogVariantMetadata(sku,displayName,gtin,mpn,attributes); }
    }
    @Schema(name = "CatalogCategoryCreate")
    record CategoryCreate(
            @Schema(description = "Immutable client-selected resource UUID", requiredMode = Schema.RequiredMode.REQUIRED, type = "string", format = "uuid") @NotNull UUID id,
            @Schema(description = "Parent Category UUID in this Tenant; null denotes a root", requiredMode = Schema.RequiredMode.NOT_REQUIRED, type = "string", format = "uuid", nullable = true) UUID parentCategoryId,
            @Schema(description = "Required nonblank name; surrounding Unicode whitespace is normalized", requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 160) @NotNull String name,
            @Schema(description = "Preserved URL-oriented identifier; no normalization", requiredMode = Schema.RequiredMode.REQUIRED, minLength = 2, maxLength = 256, pattern = "^(?:[A-Za-z0-9_-]+)$") @NotNull String slug,
            @Schema(description = "Optional description, at most 4000 Unicode code points", maxLength = 4000, nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED) String description) {}
    @Schema(name = "CatalogCategoryUpdate")
    record CategoryUpdate(
            @Schema(description = "Previously observed positive revision", requiredMode = Schema.RequiredMode.REQUIRED, type = "integer", types = {"integer"}, format = "int64", minimum = "1", maximum = "9223372036854775806") @NotNull BigDecimal expectedRevision,
            @Schema(description = "Required nonblank name; surrounding Unicode whitespace is normalized", requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 160) @NotNull String name,
            @Schema(description = "Preserved URL-oriented identifier; no normalization", requiredMode = Schema.RequiredMode.REQUIRED, minLength = 2, maxLength = 256, pattern = "^(?:[A-Za-z0-9_-]+)$") @NotNull String slug,
            @Schema(description = "Optional description, at most 4000 Unicode code points", maxLength = 4000, nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED) String description) {}
    @Schema(name = "CatalogReparentRequest")
    record ReparentRequest(
            @Schema(description = "Previously observed positive revision", requiredMode = Schema.RequiredMode.REQUIRED, type = "integer", types = {"integer"}, format = "int64", minimum = "1", maximum = "9223372036854775806") @NotNull BigDecimal expectedRevision,
            @Schema(description = "Parent Category UUID in this Tenant; null denotes a root", type = "string", format = "uuid", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED) UUID parentCategoryId) {}
    @Schema(name = "CatalogPriceRequest")
    record PriceRequest(
            @Schema(description = "Previously observed revision; only price creation accepts zero", requiredMode = Schema.RequiredMode.REQUIRED, type = "integer", types = {"integer"}, format = "int64", minimum = "0", maximum = "9223372036854775806") @NotNull BigDecimal expectedRevision,
            @Schema(description = "Exact nonnegative amount in currency minor units, never floating-point major units", type = "integer", types = {"integer"}, format = "int64", minimum = "0", maximum = "9223372036854775807", requiredMode = Schema.RequiredMode.REQUIRED) @NotNull BigDecimal minorUnits) {}
    @Schema(name = "CatalogProductView")
    record ProductView(
            @Schema(description = "Immutable client-selected resource UUID", requiredMode = Schema.RequiredMode.REQUIRED, type = "string", format = "uuid") UUID id,
            @Schema(description = "Required nonblank name; surrounding Unicode whitespace is normalized", requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 160) String name,
            @Schema(description = "Preserved URL-oriented identifier; no normalization", requiredMode = Schema.RequiredMode.REQUIRED, minLength = 2, maxLength = 256, pattern = "^(?:[A-Za-z0-9_-]+)$") String slug,
            @Schema(description = "Optional description, at most 4000 Unicode code points", requiredMode = Schema.RequiredMode.NOT_REQUIRED, maxLength = 4000, nullable = true) String description,
            @Schema(description = "Optional nonblank brand, normalized surrounding Unicode whitespace", requiredMode = Schema.RequiredMode.NOT_REQUIRED, maxLength = 120, nullable = true) String brand,
            @ArraySchema(arraySchema = @Schema(description = "Assigned Category UUIDs", requiredMode = Schema.RequiredMode.REQUIRED), uniqueItems = true, schema = @Schema(type = "string", format = "uuid")) List<UUID> categoryIds,
            @Schema(description = "Current Product lifecycle: DRAFT, ACTIVE or ARCHIVED", requiredMode = Schema.RequiredMode.REQUIRED) ProductStatus status,
            @Schema(description = "Current resource revision, starting at one", requiredMode = Schema.RequiredMode.REQUIRED, type = "integer", types = {"integer"}, format = "int64", minimum = "1", maximum = "9223372036854775807") long revision) {
        /** Projects the owner snapshot into its explicit HTTP representation and revision. */
        static ProductView of(CatalogRevision<Product> result) {
            var p=result.value(); return new ProductView(p.id(),p.name(),p.slug(),p.description(),p.brand(),p.categoryIds(),p.status(),result.revision());
        }
    }
    @Schema(name = "CatalogVariantView")
    record VariantView(
            @Schema(description = "Immutable client-selected resource UUID", requiredMode = Schema.RequiredMode.REQUIRED, type = "string", format = "uuid") UUID id,
            @Schema(description = "Immutable owning Product UUID", requiredMode = Schema.RequiredMode.REQUIRED, type = "string", format = "uuid") UUID productId,
            @Schema(description = "Merchant SKU, at most 64 Unicode code points; no surrounding whitespace or control characters", requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 64) String sku,
            @Schema(description = "Optional nonblank display name; surrounding Unicode whitespace is normalized", requiredMode = Schema.RequiredMode.NOT_REQUIRED, maxLength = 160, nullable = true) String displayName,
            @Schema(description = "Optional numeric GTIN of 8, 12, 13 or 14 digits with a valid GS1 check digit", requiredMode = Schema.RequiredMode.NOT_REQUIRED, pattern = "^(?:([0-9]{8}|[0-9]{12}|[0-9]{13}|[0-9]{14}))$", nullable = true) String gtin,
            @Schema(description = "Optional nonblank manufacturer part number; no surrounding whitespace or controls", requiredMode = Schema.RequiredMode.NOT_REQUIRED, maxLength = 70, nullable = true) String mpn,
            @ArraySchema(arraySchema = @Schema(description = "Commercial attributes with distinct keys", requiredMode = Schema.RequiredMode.REQUIRED), schema = @Schema(implementation = ProductVariantAttribute.class)) List<ProductVariantAttribute> attributes,
            @Schema(description = "Current Variant lifecycle: DRAFT, ACTIVE, INACTIVE or ARCHIVED", requiredMode = Schema.RequiredMode.REQUIRED) ProductVariantStatus status,
            @Schema(description = "Current resource revision, starting at one", requiredMode = Schema.RequiredMode.REQUIRED, type = "integer", types = {"integer"}, format = "int64", minimum = "1", maximum = "9223372036854775807") long revision) {
        /** Projects the owner snapshot into its explicit HTTP representation and revision. */
        static VariantView of(CatalogRevision<ProductVariant> result) {
            var v=result.value(); return new VariantView(v.id(),v.productId(),v.sku(),v.displayName(),v.gtin(),v.mpn(),v.attributes(),v.status(),result.revision());
        }
    }
    @Schema(name = "CatalogCategoryView")
    record CategoryView(
            @Schema(description = "Immutable client-selected resource UUID", requiredMode = Schema.RequiredMode.REQUIRED, type = "string", format = "uuid") UUID id,
            @Schema(description = "Parent Category UUID in this Tenant; null denotes a root", requiredMode = Schema.RequiredMode.NOT_REQUIRED, type = "string", format = "uuid", nullable = true) UUID parentCategoryId,
            @Schema(description = "Required nonblank name; surrounding Unicode whitespace is normalized", requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 160) String name,
            @Schema(description = "Preserved URL-oriented identifier; no normalization", requiredMode = Schema.RequiredMode.REQUIRED, minLength = 2, maxLength = 256, pattern = "^(?:[A-Za-z0-9_-]+)$") String slug,
            @Schema(description = "Optional description, at most 4000 Unicode code points", requiredMode = Schema.RequiredMode.NOT_REQUIRED, maxLength = 4000, nullable = true) String description,
            @Schema(description = "Current resource revision, starting at one", requiredMode = Schema.RequiredMode.REQUIRED, type = "integer", types = {"integer"}, format = "int64", minimum = "1", maximum = "9223372036854775807") long revision) {
        /** Projects the owner snapshot into its explicit HTTP representation and revision. */
        static CategoryView of(CatalogRevision<Category> result) {
            var c=result.value(); return new CategoryView(c.id(),c.parentCategoryId(),c.name(),c.slug(),c.description(),result.revision());
        }
    }
    @Schema(name = "CatalogPriceView")
    record PriceView(
            @Schema(description = "Owning Variant UUID", requiredMode = Schema.RequiredMode.REQUIRED, type = "string", format = "uuid") UUID variantId,
            @Schema(description = "Recognized canonical uppercase ISO 4217 currency", requiredMode = Schema.RequiredMode.REQUIRED, minLength = 3, maxLength = 3, pattern = "^(?:[A-Z]{3})$") String currencyCode,
            @Schema(description = "Exact nonnegative amount in currency minor units; JSON integer, never floating-point major units", requiredMode = Schema.RequiredMode.REQUIRED, type = "integer", types = {"integer"}, format = "int64", minimum = "0", maximum = "9223372036854775807") long minorUnits,
            @Schema(description = "Current resource revision, starting at one", requiredMode = Schema.RequiredMode.REQUIRED, type = "integer", types = {"integer"}, format = "int64", minimum = "1", maximum = "9223372036854775807") long revision) {
        /** Projects the owner snapshot into its explicit HTTP representation and revision. */
        static PriceView of(CatalogRevision<VariantBasePrice> result) {
            var p=result.value(); return new PriceView(p.variantId(),p.currencyCode(),p.minorUnits(),result.revision());
        }
    }
}
