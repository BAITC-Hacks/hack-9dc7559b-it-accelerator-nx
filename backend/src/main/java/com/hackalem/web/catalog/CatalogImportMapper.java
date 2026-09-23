package com.hackalem.web.catalog;

import com.hackalem.domain.catalog.CatalogImportDocument;
import com.hackalem.domain.catalog.ProductCertificate;
import org.mapstruct.Mapper;

import java.util.List;

/**
 * HTTP DTO → доменный документ импорта. Поля совпадают один в один, поэтому
 * маппинг генерируется: руками такой код писать незачем.
 */
@Mapper(componentModel = "spring")
public interface CatalogImportMapper {

    CatalogImportDocument toDocument(CatalogImportRequest request);

    CatalogImportDocument.Product toProduct(CatalogImportRequest.ProductRequest request);

    CatalogImportDocument.Warehouse toWarehouse(CatalogImportRequest.WarehouseRequest request);

    ProductCertificate toCertificate(CatalogImportRequest.CertificateRequest request);

    List<CatalogImportDocument.Product> toProducts(List<CatalogImportRequest.ProductRequest> requests);
}
