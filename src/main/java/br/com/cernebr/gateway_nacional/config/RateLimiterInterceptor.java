package br.com.cernebr.gateway_nacional.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.BucketProxy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Per-IP rate limiter that enforces the public Playground tier
 * (5 requests / minute / IP, shared across all {@code /api/v1/**} endpoints).
 *
 * <p>On rejection, writes a {@link ProblemDetail} (RFC 7807) directly to
 * the response with the same shape produced by {@code GlobalExceptionHandler},
 * so external consumers see a single, consistent error format regardless of
 * whether the failure was thrown by a controller or short-circuited here.</p>
 *
 * <p><b>Thread safety:</b> all fields are immutable and final.
 * {@link ProxyManager} and {@link BucketProxy} are designed for concurrent use,
 * so no synchronization is needed at the interceptor level.</p>
 */
@Slf4j
@Component
public class RateLimiterInterceptor implements HandlerInterceptor {

    private static final URI TYPE_RATE_LIMIT =
            URI.create("https://api.gateway-nacional.com.br/errors/rate-limit");

    private static final String DETAIL_MESSAGE =
            "Limite de requisições excedido. Para uso ilimitado e corporativo, " +
                    "instale nosso Gateway na sua própria infraestrutura via Docker. " +
                    "Consulte a documentação.";

    private static final String HEADER_FORWARDED_FOR = "X-Forwarded-For";
    private static final String HEADER_REAL_IP = "X-Real-IP";
    private static final String HEADER_REMAINING = "X-Rate-Limit-Remaining";
    private static final String HEADER_RETRY_AFTER = "Retry-After";

    private static final String KEY_PREFIX = "rl:public:";

    private final ProxyManager<byte[]> proxyManager;
    private final Supplier<BucketConfiguration> bucketConfiguration;
    private final ObjectMapper objectMapper;

    public RateLimiterInterceptor(ProxyManager<byte[]> rateLimiterProxyManager,
                                  Supplier<BucketConfiguration> publicTierBucketConfiguration,
                                  ObjectMapper objectMapper) {
        this.proxyManager = rateLimiterProxyManager;
        this.bucketConfiguration = publicTierBucketConfiguration;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws IOException {
        String clientIp = resolveClientIp(request);
        byte[] key = (KEY_PREFIX + clientIp).getBytes(StandardCharsets.UTF_8);

        BucketProxy bucket = proxyManager.builder().build(key, bucketConfiguration);
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);

        if (probe.isConsumed()) {
            response.setHeader(HEADER_REMAINING, Long.toString(probe.getRemainingTokens()));
            return true;
        }

        long retryAfterSeconds = Math.max(1L,
                TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()));
        log.info("Rate limit exceeded for ip={} path={} retryAfterSeconds={}",
                clientIp, request.getRequestURI(), retryAfterSeconds);

        writeTooManyRequests(request, response, retryAfterSeconds);
        return false;
    }

    private void writeTooManyRequests(HttpServletRequest request,
                                      HttpServletResponse response,
                                      long retryAfterSeconds) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.TOO_MANY_REQUESTS,
                DETAIL_MESSAGE
        );
        problem.setTitle("Limite de requisições excedido");
        problem.setType(TYPE_RATE_LIMIT);
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("timestamp", OffsetDateTime.now());
        problem.setProperty("retryAfterSeconds", retryAfterSeconds);

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(HEADER_RETRY_AFTER, Long.toString(retryAfterSeconds));
        response.setHeader(HEADER_REMAINING, "0");

        objectMapper.writeValue(response.getWriter(), problem);
    }

    /**
     * Best-effort client IP resolution.
     *
     * <p><b>Trust caveat:</b> {@code X-Forwarded-For} and {@code X-Real-IP}
     * are only meaningful when the gateway sits behind a trusted reverse proxy
     * (nginx, Cloud Load Balancer, etc.) that strips and rewrites these headers.
     * When exposed directly to the internet, clients can spoof these headers
     * to evade per-IP rate limiting. For the Playground deployment, ensure
     * the upstream proxy is the only ingress and overrides these headers.</p>
     */
    private static String resolveClientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader(HEADER_FORWARDED_FOR);
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            int comma = forwardedFor.indexOf(',');
            String firstHop = (comma > 0 ? forwardedFor.substring(0, comma) : forwardedFor).trim();
            if (!firstHop.isEmpty()) {
                return firstHop;
            }
        }
        String realIp = request.getHeader(HEADER_REAL_IP);
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }
}
