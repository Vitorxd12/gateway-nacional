package br.com.cernebr.gateway_nacional.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Centralized exception handler. Converts internal exceptions into RFC 7807
 * ProblemDetail responses, ensuring stack traces and infrastructure details
 * never leak to the client. Sensitive context is logged server-side with a
 * correlation id that is also returned to the caller for support tracing.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final URI TYPE_VALIDATION       = URI.create("https://api.gateway-nacional.com.br/errors/validation");
    private static final URI TYPE_RESOURCE_UNAVAIL = URI.create("https://api.gateway-nacional.com.br/errors/resource-unavailable");
    private static final URI TYPE_RESOURCE_NOTFOUND = URI.create("https://api.gateway-nacional.com.br/errors/resource-not-found");
    private static final URI TYPE_INTERNAL         = URI.create("https://api.gateway-nacional.com.br/errors/internal");

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException ex,
                                                          HttpServletRequest request) {
        List<Map<String, String>> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(this::toFieldErrorPayload)
                .toList();

        log.warn("Validation failure on {} {}: {} field(s) invalid",
                request.getMethod(), request.getRequestURI(), fieldErrors.size());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "A requisição contém campos inválidos. Verifique os erros e tente novamente."
        );
        problem.setTitle("Requisição inválida");
        problem.setType(TYPE_VALIDATION);
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("timestamp", OffsetDateTime.now());
        problem.setProperty("errors", fieldErrors);

        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ProblemDetail> handleMethodValidation(HandlerMethodValidationException ex,
                                                                HttpServletRequest request) {
        List<Map<String, String>> fieldErrors = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> Map.of(
                                "field", result.getMethodParameter().getParameterName() != null
                                        ? result.getMethodParameter().getParameterName()
                                        : "param",
                                "message", error.getDefaultMessage() != null
                                        ? error.getDefaultMessage()
                                        : "Valor inválido."
                        )))
                .toList();

        log.warn("Method validation failure on {} {}: {} param(s) invalid",
                request.getMethod(), request.getRequestURI(), fieldErrors.size());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "A requisição contém parâmetros inválidos. Verifique os erros e tente novamente."
        );
        problem.setTitle("Requisição inválida");
        problem.setType(TYPE_VALIDATION);
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("timestamp", OffsetDateTime.now());
        problem.setProperty("errors", fieldErrors);

        return ResponseEntity.badRequest().body(problem);
    }

    /**
     * Handles bean-validation failures triggered by {@code @Validated} on
     * controller method parameters (e.g., {@code @PathVariable @Pattern},
     * {@code @RequestParam @NotBlank @Size}). Spring lobs these as
     * {@link ConstraintViolationException} when the violation surfaces on
     * a method argument rather than a {@code @RequestBody}; without an
     * explicit handler, the catch-all maps them to a misleading 500.
     *
     * <p>Returning 400 with the offending field path and message keeps the
     * contract symmetric with {@link MethodArgumentNotValidException}
     * (which handles {@code @Valid @RequestBody}) and gives the consumer
     * actionable feedback.</p>
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraintViolation(ConstraintViolationException ex,
                                                                   HttpServletRequest request) {
        List<Map<String, String>> fieldErrors = ex.getConstraintViolations().stream()
                .map(this::toConstraintViolationPayload)
                .toList();

        log.warn("Constraint violation on {} {}: {} field(s) invalid",
                request.getMethod(), request.getRequestURI(), fieldErrors.size());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "A requisição contém parâmetros inválidos. Verifique os erros e tente novamente."
        );
        problem.setTitle("Requisição inválida");
        problem.setType(TYPE_VALIDATION);
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("timestamp", OffsetDateTime.now());
        problem.setProperty("errors", fieldErrors);

        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(ResourceUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleResourceUnavailable(ResourceUnavailableException ex,
                                                                   HttpServletRequest request) {
        String traceId = newTraceId();

        log.error("[traceId={}] Upstream provider '{}' unavailable on {} {}: {}",
                traceId, ex.getProviderName(), request.getMethod(), request.getRequestURI(),
                ex.getMessage(), ex);

        // Surface the provider's own message as the ProblemDetail "detail" —
        // every {@link ResourceUnavailableException} message in this codebase
        // is human-targeted and actionable (e.g., "DATASUS recusou a conexão
        // interna — estabelecimento sem APS"). Hiding it behind a generic
        // "tente novamente" denies the consumer the information needed to
        // decide between "retry" and "fix the query". A static fallback is
        // still used when {@code ex.getMessage()} is empty or null.
        String specificDetail = ex.getMessage();
        if (specificDetail == null || specificDetail.isBlank()) {
            specificDetail = "O provedor de dados está temporariamente indisponível. Tente novamente em alguns instantes.";
        }

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE,
                specificDetail
        );
        problem.setTitle("Serviço indisponível");
        problem.setType(TYPE_RESOURCE_UNAVAIL);
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("timestamp", OffsetDateTime.now());
        problem.setProperty("traceId", traceId);
        problem.setProperty("provider", ex.getProviderName());

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(problem);
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleResourceNotFound(ResourceNotFoundException ex,
                                                                HttpServletRequest request) {
        log.info("Resource not found ({}): {} {} → {}",
                ex.getResourceType(), request.getMethod(), request.getRequestURI(), ex.getMessage());

        // Same propagation rule as the 503 handler: surface the specific
        // message ("NCM 9999.99.99 não consta no catálogo Mercosul") so the
        // consumer learns *what* is missing, not just that *something* is.
        String detail = ex.getMessage();
        if (detail == null || detail.isBlank()) {
            detail = "Recurso não encontrado.";
        }

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, detail);
        problem.setTitle("Recurso não encontrado");
        problem.setType(TYPE_RESOURCE_NOTFOUND);
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("timestamp", OffsetDateTime.now());
        problem.setProperty("resourceType", ex.getResourceType());

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, HttpServletRequest request) {
        String traceId = newTraceId();

        log.error("[traceId={}] Unhandled exception on {} {}",
                traceId, request.getMethod(), request.getRequestURI(), ex);

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Ocorreu um erro interno inesperado. A equipe técnica foi notificada."
        );
        problem.setTitle("Erro interno");
        problem.setType(TYPE_INTERNAL);
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("timestamp", OffsetDateTime.now());
        problem.setProperty("traceId", traceId);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem);
    }

    private Map<String, String> toFieldErrorPayload(FieldError error) {
        return Map.of(
                "field", error.getField(),
                "message", error.getDefaultMessage() != null
                        ? error.getDefaultMessage()
                        : "Valor inválido."
        );
    }

    /**
     * Maps a {@link ConstraintViolation} to the same {field, message} shape
     * the other validation handlers emit. The "field" is taken from the
     * leaf node of the property path (e.g., for "findByCodigo.codigo" we
     * surface only "codigo" — the method name is server-side noise that
     * leaks the controller signature unnecessarily).
     */
    private Map<String, String> toConstraintViolationPayload(ConstraintViolation<?> violation) {
        String fullPath = violation.getPropertyPath().toString();
        int lastDot = fullPath.lastIndexOf('.');
        String leafField = lastDot >= 0 ? fullPath.substring(lastDot + 1) : fullPath;
        return Map.of(
                "field", leafField,
                "message", violation.getMessage() != null ? violation.getMessage() : "Valor inválido."
        );
    }

    private String newTraceId() {
        return UUID.randomUUID().toString();
    }
}
