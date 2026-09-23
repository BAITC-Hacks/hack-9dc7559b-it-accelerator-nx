package com.hackalem.web;

import com.hackalem.web.ProductSearchService.ProductSearchResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/products")
@Tag(name = "Products", description = "Каталог и семантический поиск товаров")
public class ProductSearchController {
    private final ProductSearchService service;

    public ProductSearchController(ProductSearchService service) {
        this.service = service;
    }

    @GetMapping("/search")
    @Operation(summary = "Найти товары по смыслу и фильтрам")
    public List<ProductSearchResult> search(
            @RequestParam @NotBlank String q,
            @RequestParam(defaultValue = "20") @Positive Integer limit,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice) {
        return service.search(q, Math.min(limit, 100), category, minPrice, maxPrice);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Добавить товар и построить embedding")
    public ProductSearchResult upsert(@Valid @RequestBody ProductRequest request) {
        return service.upsert(request);
    }

    public record ProductRequest(
            Long id,
            @NotBlank String article,
            @NotBlank String name,
            String brand,
            String category,
            BigDecimal price,
            String currency,
            Boolean stock,
            String sourceUrl,
            String specs) {
    }
}
