package br.com.cernebr.gateway_nacional.exception;

import jakarta.servlet.http.HttpServletRequest;
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

    @ExceptionHandler(ResourceUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleResourceUnavailable(ResourceUnavailableException ex,
                                                                   HttpServletRequest request) {
        String traceId = newTraceId();

        log.error("[traceId={}] Upstream provider '{}' unavailable on {} {}: {}",
                traceId, ex.getProviderName(), request.getMethod(), request.getRequestURI(),
                ex.getMessage(), ex);

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE,
                "O provedor de dados está temporariamente indisponível. Por favor, tente novamente em alguns instantes."
        );
        problem.setTitle("Serviço indisponível");
        problem.setType(TYPE_RESOURCE_UNAVAIL);
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("timestamp", OffsetDateTime.now());
        problem.setProperty("traceId", traceId);
        problem.setProperty("provider", ex.getProviderName());

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(problem);
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

    private String newTraceId() {
        return UUID.randomUUID().toString();
    }
}
