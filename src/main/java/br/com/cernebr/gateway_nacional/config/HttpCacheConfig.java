package br.com.cernebr.gateway_nacional.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.ShallowEtagHeaderFilter;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Wiring da camada de cache HTTP.
 *
 * <p>Mantém-se separado do {@link WebMvcConfig} (que é {@code @ConditionalOnProperty}
 * para o rate-limit) porque a economia de RPS via {@code Cache-Control}/{@code ETag}
 * é um ganho independente e deve estar sempre ativa em {@code /api/v1/**}.</p>
 *
 * <p><b>Restrição de path:</b> só endpoints públicos da API recebem o
 * interceptor e o filtro. Actuator ({@code /actuator/**}), Swagger UI
 * ({@code /swagger-ui/**}, {@code /v3/api-docs/**}) e probes ficam de fora,
 * tanto por princípio (telemetria não deve ser cacheada na borda) quanto por
 * custo (calcular MD5 do response body do {@code /actuator/prometheus} a cada
 * scrape é desperdício).</p>
 */
@Configuration
public class HttpCacheConfig implements WebMvcConfigurer {

    private static final String API_PATH = "/api/v1/**";

    private final HttpCacheInterceptor httpCacheInterceptor;

    public HttpCacheConfig(HttpCacheInterceptor httpCacheInterceptor) {
        this.httpCacheInterceptor = httpCacheInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(httpCacheInterceptor)
                .addPathPatterns(API_PATH);
    }

    /**
     * {@link ShallowEtagHeaderFilter} bufferiza a resposta, calcula MD5 e
     * compara com {@code If-None-Match}. Match → responde {@code 304 Not Modified}
     * sem corpo, economizando banda do origin para o CDN e do CDN para o cliente.
     *
     * <p><b>"Shallow" significa:</b> o ETag vem do hash do corpo já serializado,
     * não de um identificador semântico do recurso. O custo é processar a
     * resposta inteira mesmo no caso 304 — aceitável aqui, já que o corpo das
     * APIs do gateway é pequeno (CEP, CNPJ, taxas: payloads de poucos KB).</p>
     *
     * <p><b>Ordem do filtro:</b> precedência alta ({@code HIGHEST_PRECEDENCE + 100})
     * para envolver o {@code DispatcherServlet} antes que outros filtros mexam
     * no corpo, mas baixa o suficiente para um futuro filtro de auth/logging
     * registrado com {@code HIGHEST_PRECEDENCE} ainda rodar primeiro.</p>
     */
    @Bean
    public FilterRegistrationBean<ShallowEtagHeaderFilter> etagFilter() {
        FilterRegistrationBean<ShallowEtagHeaderFilter> registration =
                new FilterRegistrationBean<>(new ShallowEtagHeaderFilter());
        registration.addUrlPatterns("/api/v1/*");
        registration.setName("etagFilter");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 100);
        return registration;
    }
}
