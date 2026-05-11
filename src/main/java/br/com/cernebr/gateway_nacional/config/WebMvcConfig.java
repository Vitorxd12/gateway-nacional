package br.com.cernebr.gateway_nacional.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Wires Spring MVC interceptors. The rate limiter is registered only against
 * {@code /api/v1/**} so that operational endpoints (Actuator probes, Prometheus
 * scrape, Swagger UI, OpenAPI doc) remain unthrottled.
 *
 * <p><b>Exclusões:</b> {@code /api/v1/status} é deliberadamente excluído —
 * é o endpoint que status pages e uptime monitors externos batem
 * (1×/min × N monitors). Aplicar o limite Playground de 5 req/min ali
 * derrubaria a observabilidade pública do gateway no exato momento em que
 * ela é mais necessária (incidente em curso → mais checks externos).</p>
 */
@Configuration
@ConditionalOnProperty(prefix = "gateway.rate-limit", name = "enabled", havingValue = "true")
public class WebMvcConfig implements WebMvcConfigurer {

    private final RateLimiterInterceptor rateLimiterInterceptor;

    public WebMvcConfig(RateLimiterInterceptor rateLimiterInterceptor) {
        this.rateLimiterInterceptor = rateLimiterInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimiterInterceptor)
                .addPathPatterns("/api/v1/**")
                .excludePathPatterns("/api/v1/status");
    }
}
