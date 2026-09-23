package com.hackalem.web.catalog;

import com.hackalem.ai.catalog.CatalogEmbeddingProvider;
import com.hackalem.domain.catalog.CatalogProduct;
import com.hackalem.domain.catalog.CatalogQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/**
 * Публичное чтение каталога.
 *
 * Эволюция контракта относительно первоначального ProductSearchController:
 *  • GET /api/products/search сохранён, но отвечает объектом с версией каталога,
 *    а не голым массивом, и читает только активную версию;
 *  • добавлен GET /api/products/{article} — точный поиск без вызова модели;
 *  • POST /api/products удалён: запись в каталог теперь только админская,
 *    через /api/admin/catalog/imports с заданием и версионированием.
 */
@RestController
@RequestMapping("/api/products")
@Tag(name = "Products", description = "Каталог: точный артикул и семантический поиск")
public class ProductCatalogController {

    private static final int MAX_LIMIT = 50;

    private final CatalogQueryService catalog;
    private final CatalogResponseMapper mapper;
    private final CatalogEmbeddingProvider embeddings;

    public ProductCatalogController(CatalogQueryService catalog, CatalogResponseMapper mapper,
                                    CatalogEmbeddingProvider embeddings) {
        this.catalog = catalog;
        this.mapper = mapper;
        this.embeddings = embeddings;
    }

    @GetMapping("/search")
    @Operation(summary = "Найти товары по смыслу и фильтрам в активной версии каталога",
            description = "Точный артикул, затем лексический поиск с опечатками, затем ограниченный семантический поиск.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Результаты поиска"),
            @ApiResponse(responseCode = "503", description = "Индекс каталога не готов или построен другой моделью")
    })
    public CatalogResponses.ProductSearchResponse search(
            @RequestParam @NotBlank String q,
            @RequestParam(defaultValue = "20") @Positive Integer limit,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String brand,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice) {
        CatalogQueryService.SearchResult result = catalog.search(q, limit,
                category, brand, minPrice, maxPrice);
        return mapper.toSearchResponse(result.items(), result.version(), embeddings.vectorSpace(), result.mode(), result.warnings());
    }

    @GetMapping("/{article}")
    @Operation(summary = "Карточка товара по точному артикулу",
            description = "Ведущие нули значимы. Отсутствующий артикул возвращает 404, "
                    + "похожий товар вместо него не подставляется.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Карточка товара"),
            @ApiResponse(responseCode = "404", description = "Артикула нет в активном каталоге")
    })
    public CatalogResponses.ProductResponse byArticle(@PathVariable String article) {
        CatalogProduct product = catalog.findByArticle(article);
        return mapper.toProduct(product);
    }
}
