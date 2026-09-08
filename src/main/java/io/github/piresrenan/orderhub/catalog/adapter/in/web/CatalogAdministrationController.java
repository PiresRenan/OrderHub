package io.github.piresrenan.orderhub.catalog.adapter.in.web;

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
@RestController
@RequestMapping(value="/catalog",produces=MediaType.APPLICATION_JSON_VALUE)
public final class CatalogAdministrationController {
    private final CatalogAdministrationService products;
    private final CatalogCategoryAdministrationService categories;
    private final CatalogPricingAdministrationService prices;
    private final CatalogAdministrationReadService reads;
    public CatalogAdministrationController(CatalogAdministrationService products,CatalogCategoryAdministrationService categories,
            CatalogPricingAdministrationService prices,CatalogAdministrationReadService reads) {
        this.products=products; this.categories=categories; this.prices=prices; this.reads=reads;
    }
    @PostMapping(value="/products",consumes=MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ProductView> createProduct(TrustedActorContext actor,@Valid @RequestBody ProductCreate request) {
        var result=ProductView.of(products.createProduct(context(actor),request.id(),request.metadata()));
        return ResponseEntity.created(URI.create("/catalog/products/"+result.id())).body(result);
    }
    @PutMapping(value="/products/{id}/metadata",consumes=MediaType.APPLICATION_JSON_VALUE)
    ProductView updateProduct(TrustedActorContext actor,@PathVariable UUID id,@Valid @RequestBody ProductUpdate request) {
        return ProductView.of(products.updateProduct(context(actor),id,integer(request.expectedRevision()),request.metadata()));
    }
    @PostMapping(value="/products/{id}/activate",consumes=MediaType.APPLICATION_JSON_VALUE)
    ProductView activateProduct(TrustedActorContext actor,@PathVariable UUID id,@Valid @RequestBody RevisionRequest request) {
        return ProductView.of(products.activateProduct(context(actor),id,integer(request.expectedRevision())));
    }
    @PostMapping(value="/products/{id}/archive",consumes=MediaType.APPLICATION_JSON_VALUE)
    ProductView archiveProduct(TrustedActorContext actor,@PathVariable UUID id,@Valid @RequestBody RevisionRequest request) {
        return ProductView.of(products.archiveProduct(context(actor),id,integer(request.expectedRevision())));
    }
    @PutMapping(value="/products/{id}/categories",consumes=MediaType.APPLICATION_JSON_VALUE)
    ProductView assignCategories(TrustedActorContext actor,@PathVariable UUID id,@Valid @RequestBody AssignmentsRequest request) {
        return ProductView.of(products.assignCategories(context(actor),id,integer(request.expectedRevision()),request.categoryIds()));
    }
    @GetMapping("/products/{id}")
    ProductView product(TrustedActorContext actor,@PathVariable UUID id) { return ProductView.of(products.product(context(actor),id)); }
    @GetMapping("/products")
    List<CatalogProductSummary> products(TrustedActorContext actor,@RequestParam(required=false) UUID afterId,@RequestParam(defaultValue="50") int limit) {
        return reads.products(context(actor),afterId,limit);
    }
    @PostMapping(value="/products/{productId}/variants",consumes=MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<VariantView> createVariant(TrustedActorContext actor,@PathVariable UUID productId,@Valid @RequestBody VariantCreate request) {
        var result=VariantView.of(products.createVariant(context(actor),productId,request.id(),request.metadata()));
        return ResponseEntity.created(URI.create("/catalog/variants/"+result.id())).body(result);
    }
    @GetMapping("/products/{productId}/variants")
    List<CatalogVariantSummary> variants(TrustedActorContext actor,@PathVariable UUID productId,
            @RequestParam(required=false) UUID afterId,@RequestParam(defaultValue="50") int limit) {
        return reads.variants(context(actor),productId,afterId,limit);
    }
    @GetMapping("/variants/{id}")
    VariantView variant(TrustedActorContext actor,@PathVariable UUID id) { return VariantView.of(products.variant(context(actor),id)); }
    @PutMapping(value="/variants/{id}/metadata",consumes=MediaType.APPLICATION_JSON_VALUE)
    VariantView updateVariant(TrustedActorContext actor,@PathVariable UUID id,@Valid @RequestBody VariantUpdate request) {
        return VariantView.of(products.updateVariant(context(actor),id,integer(request.expectedRevision()),request.metadata()));
    }
    @PostMapping(value="/variants/{id}/activate",consumes=MediaType.APPLICATION_JSON_VALUE)
    VariantView activateVariant(TrustedActorContext actor,@PathVariable UUID id,@Valid @RequestBody RevisionRequest request) {
        return VariantView.of(products.activateVariant(context(actor),id,integer(request.expectedRevision())));
    }
    @PostMapping(value="/variants/{id}/deactivate",consumes=MediaType.APPLICATION_JSON_VALUE)
    VariantView deactivateVariant(TrustedActorContext actor,@PathVariable UUID id,@Valid @RequestBody RevisionRequest request) {
        return VariantView.of(products.deactivateVariant(context(actor),id,integer(request.expectedRevision())));
    }
    @PostMapping(value="/variants/{id}/archive",consumes=MediaType.APPLICATION_JSON_VALUE)
    VariantView archiveVariant(TrustedActorContext actor,@PathVariable UUID id,@Valid @RequestBody RevisionRequest request) {
        return VariantView.of(products.archiveVariant(context(actor),id,integer(request.expectedRevision())));
    }
    @PostMapping(value="/categories",consumes=MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<CategoryView> createCategory(TrustedActorContext actor,@Valid @RequestBody CategoryCreate request) {
        var result=CategoryView.of(categories.create(context(actor),request.id(),request.parentCategoryId(),request.name(),request.slug(),request.description()));
        return ResponseEntity.created(URI.create("/catalog/categories/"+result.id())).body(result);
    }
    @PutMapping(value="/categories/{id}/metadata",consumes=MediaType.APPLICATION_JSON_VALUE)
    CategoryView updateCategory(TrustedActorContext actor,@PathVariable UUID id,@Valid @RequestBody CategoryUpdate request) {
        return CategoryView.of(categories.metadata(context(actor),id,integer(request.expectedRevision()),request.name(),request.slug(),request.description()));
    }
    @PutMapping(value="/categories/{id}/parent",consumes=MediaType.APPLICATION_JSON_VALUE)
    CategoryView reparentCategory(TrustedActorContext actor,@PathVariable UUID id,@Valid @RequestBody ReparentRequest request) {
        return CategoryView.of(categories.reparent(context(actor),id,integer(request.expectedRevision()),request.parentCategoryId()));
    }
    @GetMapping("/categories/{id}")
    CategoryView category(TrustedActorContext actor,@PathVariable UUID id) { return CategoryView.of(categories.get(context(actor),id)); }
    @GetMapping("/categories")
    List<CategoryView> categories(TrustedActorContext actor,@RequestParam(required=false) UUID afterId,@RequestParam(defaultValue="50") int limit) {
        return reads.categories(context(actor),afterId,limit).stream().map(CategoryView::of).toList();
    }
    @PutMapping(value="/variants/{id}/prices/{currency}",consumes=MediaType.APPLICATION_JSON_VALUE)
    PriceView setPrice(TrustedActorContext actor,@PathVariable UUID id,@PathVariable String currency,@Valid @RequestBody PriceRequest request) {
        return PriceView.of(prices.set(context(actor),id,currency,integer(request.expectedRevision()),integer(request.minorUnits())));
    }
    @GetMapping("/variants/{id}/prices/{currency}")
    PriceView price(TrustedActorContext actor,@PathVariable UUID id,@PathVariable String currency) { return PriceView.of(prices.get(context(actor),id,currency)); }

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
    record ProductCreate(@NotNull UUID id,@NotNull String name,@NotNull String slug,String description,String brand) {
        CatalogProductMetadata metadata() { return new CatalogProductMetadata(name,slug,description,brand); }
    }
    record ProductUpdate(@NotNull BigDecimal expectedRevision,@NotNull String name,@NotNull String slug,String description,String brand) {
        CatalogProductMetadata metadata() { return new CatalogProductMetadata(name,slug,description,brand); }
    }
    record RevisionRequest(@NotNull BigDecimal expectedRevision) {}
    record AssignmentsRequest(@NotNull BigDecimal expectedRevision,@NotNull @Size(max=100) List<@NotNull UUID> categoryIds) {}
    record VariantCreate(@NotNull UUID id,@NotNull @Size(max=160) String sku,String displayName,String gtin,String mpn,
            @NotNull @Size(max=50) List<@NotNull ProductVariantAttribute> attributes) {
        CatalogVariantMetadata metadata() { return new CatalogVariantMetadata(sku,displayName,gtin,mpn,attributes); }
    }
    record VariantUpdate(@NotNull BigDecimal expectedRevision,@NotNull @Size(max=160) String sku,String displayName,String gtin,String mpn,
            @NotNull @Size(max=50) List<@NotNull ProductVariantAttribute> attributes) {
        CatalogVariantMetadata metadata() { return new CatalogVariantMetadata(sku,displayName,gtin,mpn,attributes); }
    }
    record CategoryCreate(@NotNull UUID id,UUID parentCategoryId,@NotNull String name,@NotNull String slug,String description) {}
    record CategoryUpdate(@NotNull BigDecimal expectedRevision,@NotNull String name,@NotNull String slug,String description) {}
    record ReparentRequest(@NotNull BigDecimal expectedRevision,UUID parentCategoryId) {}
    record PriceRequest(@NotNull BigDecimal expectedRevision,@NotNull BigDecimal minorUnits) {}
    record ProductView(UUID id,String name,String slug,String description,String brand,List<UUID> categoryIds,ProductStatus status,long revision) {
        static ProductView of(CatalogRevision<Product> result) {
            var p=result.value(); return new ProductView(p.id(),p.name(),p.slug(),p.description(),p.brand(),p.categoryIds(),p.status(),result.revision());
        }
    }
    record VariantView(UUID id,UUID productId,String sku,String displayName,String gtin,String mpn,List<ProductVariantAttribute> attributes,ProductVariantStatus status,long revision) {
        static VariantView of(CatalogRevision<ProductVariant> result) {
            var v=result.value(); return new VariantView(v.id(),v.productId(),v.sku(),v.displayName(),v.gtin(),v.mpn(),v.attributes(),v.status(),result.revision());
        }
    }
    record CategoryView(UUID id,UUID parentCategoryId,String name,String slug,String description,long revision) {
        static CategoryView of(CatalogRevision<Category> result) {
            var c=result.value(); return new CategoryView(c.id(),c.parentCategoryId(),c.name(),c.slug(),c.description(),result.revision());
        }
    }
    record PriceView(UUID variantId,String currencyCode,long minorUnits,long revision) {
        static PriceView of(CatalogRevision<VariantBasePrice> result) {
            var p=result.value(); return new PriceView(p.variantId(),p.currencyCode(),p.minorUnits(),result.revision());
        }
    }
}
