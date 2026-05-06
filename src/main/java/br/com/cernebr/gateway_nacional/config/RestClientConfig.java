package br.com.cernebr.gateway_nacional.config;

import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.Executors;

/**
 * HTTP client infrastructure for outbound calls to upstream providers
 * (ViaCEP, BrasilAPI, AwesomeAPI).
 *
 * <p>Uses {@link JdkClientHttpRequestFactory} backed by the JDK's
 * {@link HttpClient}, configured with a virtual-thread executor so each
 * outbound request is carried by a virtual thread end-to-end. Combined with
 * Tomcat's virtual-thread executor, the entire request lifecycle runs on
 * Loom — no platform-thread pinning under I/O-bound load.</p>
 *
 * <p>Defense in depth on top of Resilience4j's {@code TimeLimiter}:
 * a hard connect timeout of 2s and read timeout of 5s ensure the underlying
 * socket cannot hang indefinitely even if the timeout supervisor misbehaves.</p>
 */
@Configuration
public class RestClientConfig {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    /**
     * Singleton factory: the underlying {@link HttpClient} is heavyweight and
     * thread-safe, so it's reused across all outbound clients.
     */
    @Bean
    public ClientHttpRequestFactory clientHttpRequestFactory() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();

        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(READ_TIMEOUT);
        return factory;
    }

    /**
     * Prototype-scoped to mirror Spring Boot's auto-configured builder behavior:
     * each injection point receives a fresh builder so per-client {@code baseUrl}
     * customization on one bean does not leak into another.
     */
    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    public RestClient.Builder restClientBuilder(ClientHttpRequestFactory requestFactory) {
        return RestClient.builder().requestFactory(requestFactory);
    }
}
