package com.hackalem.web.catalog;

import com.hackalem.domain.catalog.CatalogImportError;
import com.hackalem.domain.catalog.CatalogImportJob;
import com.hackalem.domain.catalog.CatalogProduct;
import com.hackalem.domain.catalog.CatalogVersion;
import com.hackalem.domain.catalog.Decimals;
import com.hackalem.domain.catalog.ProductCertificate;
import com.hackalem.domain.catalog.ProductOffer;
import com.hackalem.domain.catalog.ProductStock;
import com.hackalem.domain.catalog.WarehouseAvailability;
import com.hackalem.web.catalog.CatalogResponses.CertificateResponse;
import com.hackalem.web.catalog.CatalogResponses.ImportIssueResponse;
import com.hackalem.web.catalog.CatalogResponses.ImportJobResponse;
import com.hackalem.web.catalog.CatalogResponses.OfferResponse;
import com.hackalem.web.catalog.CatalogResponses.ProductResponse;
import com.hackalem.web.catalog.CatalogResponses.StockResponse;
import com.hackalem.web.catalog.CatalogResponses.WarehouseResponse;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Домен → HTTP-ответ.
 *
 * Здесь маппинг написан руками намеренно: у денег и количеств разные контракты
 * сериализации ("1500.00" против "12"), а статусы цены и остатка — производные,
 * а не поля. Генератору пришлось бы задавать квалификатор почти на каждое поле,
 * и читать это было бы сложнее, чем прямой код. DTO импорта, где поля совпадают
 * один в один, генерируется MapStruct'ом — см. {@link CatalogImportMapper}.
 */
@Component
public class CatalogResponseMapper {

    public ProductResponse toProduct(CatalogProduct product) {
        return new ProductResponse(
                String.valueOf(product.id()),
                product.supplierId(),
                product.article(),
                product.name(),
                product.brand(),
                product.category(),
                product.unit(),
                Decimals.quantity(product.minimumQuantity()),
                Decimals.quantity(product.stepQuantity()),
                product.specs() == null ? Map.of() : product.specs(),
                toCertificates(product.certificates()),
                product.sourceUrl(),
                product.sourceVersion(),
                product.synthetic(),
                String.valueOf(product.catalogVersionId()),
                toOffer(product.offer()),
                toStock(product.stock()),
                product.score());
    }

    public CatalogResponses.ProductSearchResponse toSearchResponse(List<CatalogProduct> products,
                                                                   CatalogVersion version,
                                                                   String vectorSpace, String mode, List<String> warnings) {
        return new CatalogResponses.ProductSearchResponse(
                String.valueOf(version.id()),
                version.sourceVersion(),
                vectorSpace,
                products.size(),
                products.stream().map(this::toProduct).toList(), mode, warnings);
    }

    public ImportJobResponse toJob(CatalogImportJob job) {
        return new ImportJobResponse(
                String.valueOf(job.id()),
                job.jobType(),
                job.status().name(),
                job.sourceVersion(),
                job.requestedBy(),
                job.vectorSpace(),
                job.catalogVersionId() == null ? null : String.valueOf(job.catalogVersionId()),
                job.catalogVersionStatus() == null ? null : job.catalogVersionStatus().name(),
                job.embeddingStatus() == null ? null : job.embeddingStatus().name(),
                job.totalProducts(),
                job.importedProducts(),
                job.embeddedProducts(),
                toIssues(job.errors()),
                toIssues(job.warnings()),
                text(job.createdAt()),
                text(job.startedAt()),
                text(job.finishedAt()));
    }

    public List<ImportIssueResponse> toIssues(List<CatalogImportError> issues) {
        return issues == null ? List.of() : issues.stream()
                .map(issue -> new ImportIssueResponse(issue.code(), issue.path(), issue.message()))
                .toList();
    }

    private OfferResponse toOffer(ProductOffer offer) {
        if (offer == null || !offer.known()) {
            // Неизвестная цена остаётся неизвестной: ноль здесь был бы выдумкой.
            return new OfferResponse(null, offer == null ? null : offer.currency(), "UNKNOWN",
                    offer == null ? null : offer.sourceVersion(),
                    offer == null ? null : text(offer.observedAt()));
        }
        return new OfferResponse(Decimals.money(offer.price()), offer.currency(), "KNOWN",
                offer.sourceVersion(), text(offer.observedAt()));
    }

    private StockResponse toStock(ProductStock stock) {
        if (stock == null) {
            return new StockResponse("UNKNOWN", null, "UNKNOWN", List.of());
        }
        return new StockResponse(stock.status().name(),
                Decimals.quantity(stock.availableQuantity()),
                stock.quantityBasis().name(),
                stock.warehouses().stream().map(this::toWarehouse).toList());
    }

    private WarehouseResponse toWarehouse(WarehouseAvailability warehouse) {
        return new WarehouseResponse(warehouse.warehouseId(), warehouse.status().name(),
                Decimals.quantity(warehouse.availableQuantity()), warehouse.eligible(),
                text(warehouse.observedAt()));
    }

    private List<CertificateResponse> toCertificates(List<ProductCertificate> certificates) {
        return certificates == null ? List.of() : certificates.stream()
                .map(certificate -> new CertificateResponse(certificate.id(), certificate.url(),
                        certificate.version(), certificate.synthetic()))
                .toList();
    }

    private static String text(Instant instant) {
        return instant == null ? null : instant.toString();
    }
}
