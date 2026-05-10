package br.com.cernebr.gateway_nacional.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declara a política de {@code Cache-Control} HTTP de um endpoint, lida e
 * aplicada pelo {@link HttpCacheInterceptor}.
 *
 * <p>O objetivo é empurrar a resposta para fora do Spring: caches compartilhados
 * (Cloudflare, CloudFront, Vercel Edge, nginx proxy_cache) absorvem o tráfego
 * antes de tocar no Tomcat. Para um endpoint com cache hit no edge, o gateway
 * nem é invocado — economiza thread, conexão Redis, ciclo de provider e quota.</p>
 *
 * <p><b>Por que {@code s-maxage} e não {@code max-age}:</b> {@code s-maxage}
 * só vale para {@code shared caches} (CDN, proxy), nunca para o browser do
 * usuário final. Isso é desejável numa API B2B: o operador da CDN é quem
 * controla a invalidação, e clientes individuais nunca seguram uma resposta
 * obsoleta no cache local entre uma request e outra.</p>
 *
 * <p><b>{@code stale-while-revalidate}:</b> quando o {@code s-maxage} expira,
 * o CDN serve a resposta velha por mais {@code staleWhileRevalidate} segundos
 * enquanto refaz a request em background. Elimina o pico de latência na borda
 * do TTL — exatamente o mesmo objetivo do refresh-ahead da Sprint 3, só que
 * uma camada acima.</p>
 *
 * <p><b>Aplicar no método &gt; aplicar na classe:</b> a presença de
 * {@code @HttpCache} no método sobrescreve totalmente a presença na classe.
 * Endpoints não anotados não recebem nenhum {@code Cache-Control} desta camada
 * — ficam como estão hoje (geralmente sem header, o que browsers/CDNs
 * interpretam como heuristic-cacheable; ainda assim, prefira ser explícito).</p>
 *
 * <p><b>Não aplicar em:</b> endpoints que retornam dados gerados sob demanda
 * (relatórios consolidados), endpoints autenticados com payload por-usuário,
 * ou rotas de mutação. Se houver dúvida, não anote — ausência é o default seguro.</p>
 */
@Documented
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface HttpCache {

    /**
     * {@code s-maxage} em segundos — janela em que o CDN serve a resposta
     * direto, sem revalidar com o origin.
     */
    long sMaxAge();

    /**
     * {@code stale-while-revalidate} em segundos — janela adicional em que o
     * CDN ainda serve a resposta velha enquanto dispara a revalidação.
     * Default {@code 60s} cobre o caso comum sem ser intrusivo; suba quando
     * latência do origin for alta (scrapers, sidecars).
     */
    long staleWhileRevalidate() default 60L;

    /**
     * {@code max-age} em segundos para o browser do usuário. Default {@code 0}
     * desliga o cache no cliente final, alinhado com o uso B2B da API. Suba
     * apenas para endpoints públicos puramente referenciais (calendário,
     * tabelas estáticas) onde duplicar a resposta no client é aceitável.
     */
    long maxAge() default 0L;
}
