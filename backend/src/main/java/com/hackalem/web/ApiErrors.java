package com.hackalem.web;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import jakarta.validation.ConstraintViolationException;
import java.util.UUID;
@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> domain(ApiException e) { return problem(e.status,e.code); }
    @ExceptionHandler({MethodArgumentNotValidException.class, HandlerMethodValidationException.class,
        HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class, ConstraintViolationException.class})
    public ResponseEntity<ProblemDetail> invalid(Exception e) { return problem(400,"invalid_request"); }
    public static ResponseEntity<ProblemDetail> problem(int status,String code) {
        var detail=ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status),code);
        detail.setProperty("code",code);
        detail.setProperty("correlationId",UUID.randomUUID().toString());
        detail.setProperty("retryable",status==429 || status==503);
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(detail);
    }
}
