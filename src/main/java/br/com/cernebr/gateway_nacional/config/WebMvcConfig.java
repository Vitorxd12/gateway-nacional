package br.com.cernebr.gateway_nacional.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Wires Spring MVC interceptors. The rate limiter is registered only against
 * {@code /api/v1/**} so that operational endpoints (Actuator probes, Prometheus
 * scrape, Swagger UI, OpenAPI doc) remain unthrottled.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final RateLimiterInterceptor rateLimiterInterceptor;

    public WebMvcConfig(RateLimiterInterceptor rateLimiterInterceptor) {
        this.rateLimiterInterceptor = rateLimiterInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimiterInterceptor)
                .addPathPatterns("/api/v1/**");
    }
}
