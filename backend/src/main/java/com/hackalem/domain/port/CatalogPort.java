package com.hackalem.domain.port;
import static com.hackalem.domain.port.Contracts.*;
public interface CatalogPort {
    ProductResultSet search(SearchQuery query, TrustedScope scope);
    ProductDetails getProduct(String article, TrustedScope scope);
}
