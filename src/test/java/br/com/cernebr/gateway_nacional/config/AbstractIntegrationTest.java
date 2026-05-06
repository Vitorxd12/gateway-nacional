package br.com.cernebr.gateway_nacional.config;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared infrastructure for integration tests:
 * <ul>
 *   <li>A real Redis (testcontainers, {@code redis:7-alpine}) backing the
 *       Spring cache layer — proves the {@code @Cacheable} path is wired.</li>
 *   <li>A shared {@link WireMockServer} on a dynamic port, exposed to the
 *       application as {@code ${wiremock.server.port}} so the test
 *       {@code application-test.yml} can rewrite every provider base URL
 *       to point at it.</li>
 * </ul>
 *
 * <p>Both are started in a static initializer rather than via
 * {@code @Container} / {@code @RegisterExtension} so their ports are bound
 * <em>before</em> Spring evaluates {@code @DynamicPropertySource}, which is
 * essential because the providers' {@code RestClient} is built at bean
 * construction time using the resolved base URL.</p>
 *
 * <p>{@code @BeforeEach} resets WireMock stubs and clears all caches so each
 * test starts from a clean slate — without this, a {@code @Cacheable} hit
 * from a prior test would silently bypass the cascade under test.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    protected static final WireMockServer WIREMOCK =
            new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());

    protected static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    static {
        WIREMOCK.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("wiremock.server.port", WIREMOCK::port);
    }

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected CacheManager cacheManager;

    @BeforeEach
    void resetState() {
        WIREMOCK.resetAll();
        for (String name : cacheManager.getCacheNames()) {
            Cache cache = cacheManager.getCache(name);
            if (cache != null) {
                cache.clear();
            }
        }
    }
}
