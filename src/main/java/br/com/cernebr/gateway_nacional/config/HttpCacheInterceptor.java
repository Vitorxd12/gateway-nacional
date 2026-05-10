package br.com.cernebr.gateway_nacional.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

/**
 * Lê a anotação {@link HttpCache} declarada no handler e materializa o cabeçalho
 * {@code Cache-Control} (mais {@code Vary}) na resposta, para que CDNs e proxies
 * compartilhados absorvam o tráfego antes do Spring.
 *
 * <p><b>Por que {@code postHandle} e não {@code preHandle}:</b> o status final
 * só é conhecido depois que o controller executa. Setar headers em {@code preHandle}
 * marcaria como cacheável até respostas de erro lançadas por
 * {@code @ExceptionHandler}. Em {@code postHandle} já se sabe se é 200 ou outro
 * status — só cabeçalha quando vale.</p>
 *
 * <p><b>Interação com {@code @ExceptionHandler}:</b> quando o handler lança
 * exceção, {@code postHandle} não é invocado (apenas {@code afterCompletion}),
 * então respostas {@link org.springframework.http.ProblemDetail} produzidas pelo
 * {@code GlobalExceptionHandler} nunca recebem {@code Cache-Control} acidentalmente.</p>
 *
 * <p><b>Defesa contra dupla escrita:</b> se o handler já setou {@code Cache-Control}
 * manualmente (ex.: rota que precisa de {@code no-store}), o interceptor respeita
 * e não sobrescreve.</p>
 */
@Slf4j
@Component
public class HttpCacheInterceptor implements HandlerInterceptor {

    private static final String VARY_VALUE = "Accept, Accept-Encoding";

    @Override
    public void postHandle(HttpServletRequest request,
                           HttpServletResponse response,
                           Object handler,
                           @Nullable ModelAndView modelAndView) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return;
        }
        if (response.getStatus() != HttpStatus.OK.value()) {
            return;
        }
        if (response.containsHeader(HttpHeaders.CACHE_CONTROL)) {
            return;
        }

        HttpCache annotation = resolveAnnotation(handlerMethod);
        if (annotation == null) {
            return;
        }

        if (response.isCommitted()) {
            log.warn("Cannot set Cache-Control on committed response for {} {}",
                    request.getMethod(), request.getRequestURI());
            return;
        }

        response.setHeader(HttpHeaders.CACHE_CONTROL, buildCacheControl(annotation));
        response.setHeader(HttpHeaders.VARY, VARY_VALUE);
    }

    /**
     * Anotação no método tem precedência total sobre anotação na classe — a
     * declaração mais específica vence, alinhado com a semântica de outras
     * anotações Spring ({@code @RequestMapping}, {@code @PreAuthorize}).
     */
    @Nullable
    private static HttpCache resolveAnnotation(HandlerMethod handlerMethod) {
        HttpCache method = handlerMethod.getMethodAnnotation(HttpCache.class);
        if (method != null) {
            return method;
        }
        return handlerMethod.getBeanType().getAnnotation(HttpCache.class);
    }

    private static String buildCacheControl(HttpCache annotation) {
        StringBuilder sb = new StringBuilder("public");
        sb.append(", max-age=").append(annotation.maxAge());
        sb.append(", s-maxage=").append(annotation.sMaxAge());
        if (annotation.staleWhileRevalidate() > 0L) {
            sb.append(", stale-while-revalidate=").append(annotation.staleWhileRevalidate());
        }
        return sb.toString();
    }
}
