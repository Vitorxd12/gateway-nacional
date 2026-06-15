package br.com.cernebr.gateway_nacional.licitacoes.inteligencia.ingest;

import br.com.cernebr.gateway_nacional.licitacoes.inteligencia.config.IntelProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Job recorrente de ingestão (cron-gated). Roda na madrugada e reprocessa a
 * janela recente do município-foco — idempotente, então re-rodar só atualiza.
 *
 * <p><b>MVP:</b> o alvo é o {@code seed.municipio-ibge} configurado (Aracaju por
 * padrão). A varredura nacional por UF/competência é evolução futura — o
 * {@link ParticipacaoIngestionService#ingerirMunicipioJanela} já é o ponto de
 * extensão.</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "gateway.licitacoes.inteligencia", name = "enabled", havingValue = "true")
public class ParticipacaoIngestionScheduler {

    private final ParticipacaoIngestionService ingestion;
    private final IntelProperties props;

    public ParticipacaoIngestionScheduler(ParticipacaoIngestionService ingestion, IntelProperties props) {
        this.ingestion = ingestion;
        this.props = props;
    }

    @Scheduled(cron = "${gateway.licitacoes.inteligencia.ingestao.cron}")
    public void rodar() {
        String municipio = props.seed() != null ? props.seed().municipioIbge() : null;
        if (municipio == null || municipio.isBlank()) {
            log.info("[LIC-INTEL] Scheduler sem municipio-foco configurado — nada a fazer.");
            return;
        }
        log.info("[LIC-INTEL] Scheduler disparado para municipio={}", municipio);
        ingestion.ingerirMunicipioJanela(municipio, props.ingestao().janelaDias());
    }
}
