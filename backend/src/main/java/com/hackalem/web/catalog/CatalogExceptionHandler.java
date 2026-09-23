package com.hackalem.web.catalog;

import com.hackalem.domain.catalog.CatalogConflictException;
import com.hackalem.domain.catalog.CatalogIndexNotReadyException;
import com.hackalem.domain.catalog.CatalogValidationException;
import com.hackalem.domain.catalog.ProductNotFoundException;
import com.hackalem.security.AdminAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;

/**
 * Ошибки каталога в формате ProblemDetail со стабильным кодом.
 *
 * Advice намеренно ограничен контроллерами каталога: общий обработчик для всего
 * API появится вместе с остальными доменами и не должен конфликтовать с этим.
 */
@RestControllerAdvice(assignableTypes = {ProductCatalogController.class, CatalogAdminController.class})
public class CatalogExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(CatalogExceptionHandler.class);

    private final CatalogResponseMapper mapper;

    public CatalogExceptionHandler(CatalogResponseMapper mapper) {
        this.mapper = mapper;
    }

    @ExceptionHandler(ProductNotFoundException.class)
    public ProblemDetail handleNotFound(ProductNotFoundException ex) {
        ProblemDetail problem = problem(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", ex.getMessage());
        problem.setProperty("article", ex.article());
        // Явно: подменять отсутствующий артикул похожим товаром нельзя.
        problem.setProperty("substituted", false);
        return problem;
    }

    @ExceptionHandler(CatalogValidationException.class)
    public ProblemDetail handleValidation(CatalogValidationException ex) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "CATALOG_IMPORT_INVALID",
                "Документ импорта отклонён: каталог не изменён");
        problem.setProperty("errors", mapper.toIssues(ex.errors()));
        return problem;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleBeanValidation(MethodArgumentNotValidException ex) {
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.of(
                        "code", "FIELD_INVALID",
                        "path", error.getField(),
                        "message", String.valueOf(error.getDefaultMessage())))
                .toList();
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "REQUEST_INVALID",
                "Запрос не прошёл структурную проверку");
        problem.setProperty("errors", errors);
        return problem;
    }

    @ExceptionHandler(CatalogIndexNotReadyException.class)
    public ProblemDetail handleIndexNotReady(CatalogIndexNotReadyException ex) {
        ProblemDetail problem = problem(HttpStatus.SERVICE_UNAVAILABLE, ex.code(), ex.getMessage());
        problem.setProperty("retryable", true);
        return problem;
    }

    @ExceptionHandler(CatalogConflictException.class)
    public ProblemDetail handleConflict(CatalogConflictException ex) {
        return problem(HttpStatus.CONFLICT, ex.code(), ex.getMessage());
    }

    @ExceptionHandler(AdminAccessException.class)
    public ProblemDetail handleAdminAccess(AdminAccessException ex) {
        HttpStatus status = ex.configurationIssue() ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.FORBIDDEN;
        return problem(status, ex.code(), ex.getMessage());
    }

    @ExceptionHandler(RejectedExecutionException.class)
    public ProblemDetail handleQueueFull(RejectedExecutionException ex) {
        log.warn("Очередь импорта каталога заполнена: {}", ex.getMessage());
        ProblemDetail problem = problem(HttpStatus.SERVICE_UNAVAILABLE, "CATALOG_IMPORT_QUEUE_FULL",
                "Очередь импорта заполнена: повторите позже");
        problem.setProperty("retryable", true);
        return problem;
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", code);
        return problem;
    }
}
