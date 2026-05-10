package br.com.cernebr.gateway_nacional.financeiro.pix.client;

import br.com.cernebr.gateway_nacional.exception.ResourceUnavailableException;
import br.com.cernebr.gateway_nacional.financeiro.pix.dto.PixParticipanteResponse;
import br.com.cernebr.gateway_nacional.financeiro.pix.dto.PixParticipantesResponse;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * Provider PRIMÁRIO de PIX participantes — BrasilAPI ({@code /api/pix/v1/participants}).
 *
 * <p>Estratégia de paridade definida pela RULE B: a BrasilAPI é o nosso
 * primário porque ela já faz o trabalho pesado de baixar e parsear o CSV
 * do BCB internamente, com cache próprio e fallback de data. Quando ela
 * está saudável, dispensa-se o tráfego de download/parse aqui no gateway.</p>
 *
 * <p>O endpoint upstream devolve um {@code List<>} cru sem metadados; aqui
 * envelopamos em {@link PixParticipantesResponse} usando a data <em>atual</em>
 * como {@code dataReferencia} — assumimos que a BrasilAPI já lidou com a
 * lógica de "qual é o snapshot mais recente" upstream. Quando o fallback BCB
 * direto entra em ação, a {@code dataReferencia} reflete a data efetiva do
 * arquivo encontrado, distinguindo as duas situações.</p>
 */
@Slf4j
@Component
public class BrasilApiPixClient implements PixParticipantesClientProvider {

    public static final String PROVIDER_NAME = "BrasilAPI";

    private final RestClient restClient;

    public BrasilApiPixClient(RestClient.Builder builder,
                              @Value("${gateway.pix.brasilapi.base-url:https://brasilapi.com.br}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    @Override
    @CircuitBreaker(name = "pixBrasilApiCB", fallbackMethod = "fallback")
    public PixParticipantesResponse fetchAll() {
        List<BrasilApiPixParticipante> raw = restClient.get()
                .uri("/api/pix/v1/participants")
                .retrieve()
                .body(new ParameterizedTypeReference<List<BrasilApiPixParticipante>>() {});

        if (raw == null || raw.isEmpty()) {
            throw new ResourceUnavailableException(PROVIDER_NAME,
                    "BrasilAPI retornou lista vazia para participantes do PIX.");
        }

        List<PixParticipanteResponse> participantes = raw.stream()
                .map(BrasilApiPixParticipante::toUnified)
                .toList();

        return new PixParticipantesResponse(
                participantes.size(),
                LocalDate.now(ZoneId.of("America/Sao_Paulo")),
                PROVIDER_NAME,
                participantes
        );
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @SuppressWarnings("unused")
    private PixParticipantesResponse fallback(Throwable cause) {
        log.warn("BrasilAPI fallback triggered for PIX participants cause={}", cause.toString());
        throw new ResourceUnavailableException(PROVIDER_NAME,
                "BrasilAPI indisponível ou Circuit Breaker aberto.", cause);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BrasilApiPixParticipante(
            String ispb,
            String nome,
            @JsonProperty("nome_reduzido") String nomeReduzido,
            @JsonProperty("modalidade_participacao") String modalidadeParticipacao,
            @JsonProperty("tipo_participacao") String tipoParticipacao,
            @JsonProperty("inicio_operacao") OffsetDateTime inicioOperacao
    ) {
        PixParticipanteResponse toUnified() {
            return new PixParticipanteResponse(
                    ispb, nome, nomeReduzido, modalidadeParticipacao, tipoParticipacao, inicioOperacao);
        }
    }
}
