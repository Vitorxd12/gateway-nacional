package br.com.cernebr.gateway_nacional.licitacoes.inteligencia.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Habilita {@code @Scheduled} apenas quando a Inteligência de Licitações está
 * ligada. Colocado aqui (não na classe principal) para não onerar self-hosts
 * que mantêm o módulo desligado — mesma estratégia do {@code SigtapSchedulingConfig}.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "gateway.licitacoes.inteligencia", name = "enabled", havingValue = "true")
public class IntelSchedulingConfig {
}
