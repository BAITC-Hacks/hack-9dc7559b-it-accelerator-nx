package com.hackalem.web.catalog;

import com.hackalem.domain.catalog.CatalogImportJob;
import com.hackalem.domain.catalog.CatalogImportJobRepository;
import com.hackalem.domain.catalog.CatalogImportService;
import com.hackalem.security.CatalogAdminScope;
import com.hackalem.security.TrustedScopeResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Админские операции каталога. Посетителю эти endpoint'ы недоступны: запись в
 * каталог больше не является публичной, как это было у POST /api/products.
 *
 * Проверка прав использует проверенную AUTH-01 сессию и серверную роль ADMIN.
 */
@RestController
@RequestMapping("/api/admin")
@Tag(name = "Catalog admin", description = "Импорт каталога и статус заданий (только администратор)")
public class CatalogAdminController {

    private final CatalogImportService importService;
    private final CatalogImportJobRepository jobs;
    private final CatalogImportMapper importMapper;
    private final CatalogResponseMapper responseMapper;
    private final TrustedScopeResolver scopeResolver;

    public CatalogAdminController(CatalogImportService importService,
                                  CatalogImportJobRepository jobs,
                                  CatalogImportMapper importMapper,
                                  CatalogResponseMapper responseMapper,
                                  TrustedScopeResolver scopeResolver) {
        this.importService = importService;
        this.jobs = jobs;
        this.importMapper = importMapper;
        this.responseMapper = responseMapper;
        this.scopeResolver = scopeResolver;
    }

    @PostMapping("/catalog/imports")
    @Operation(summary = "Импортировать выгрузку каталога",
            description = "Создаёт новую версию каталога и публикует её только после успешной записи. "
                    + "Повтор с тем же Idempotency-Key возвращает прежнее задание, а не второй импорт.")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Задание принято"),
            @ApiResponse(responseCode = "400", description = "Документ не прошёл проверку; каталог не изменён"),
            @ApiResponse(responseCode = "403", description = "Нет прав администратора"),
            @ApiResponse(responseCode = "503", description = "Очередь импорта заполнена или доступ не настроен")
    })
    public ResponseEntity<CatalogResponses.ImportJobResponse> startImport(
            HttpServletRequest httpRequest,
            @Parameter(description = "Ключ идемпотентности повторной отправки")
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CatalogImportRequest request) {

        CatalogAdminScope scope = scopeResolver.requireAdmin(httpRequest);
        CatalogImportJob job = importService.submit(importMapper.toDocument(request), idempotencyKey,
                scope.principal());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(responseMapper.toJob(job));
    }

    @GetMapping("/jobs/{id}")
    @Operation(summary = "Статус задания импорта")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Состояние задания"),
            @ApiResponse(responseCode = "403", description = "Нет прав администратора"),
            @ApiResponse(responseCode = "404", description = "Задание не найдено")
    })
    public CatalogResponses.ImportJobResponse jobStatus(HttpServletRequest httpRequest,
                                                        @PathVariable long id) {
        scopeResolver.requireAdmin(httpRequest);
        return jobs.find(id)
                .map(responseMapper::toJob)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Задание " + id + " не найдено"));
    }
}
