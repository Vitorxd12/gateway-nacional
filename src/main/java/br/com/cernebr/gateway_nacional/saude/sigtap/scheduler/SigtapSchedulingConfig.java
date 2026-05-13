package br.com.cernebr.gateway_nacional.saude.sigtap.scheduler;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Habilita {@code @Scheduled} apenas quando o flag SIGTAP está ligado.
 * O {@code @EnableScheduling} é colocado aqui (não na classe principal)
 * para não onerar self-hosts que mantêm o módulo desligado.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "gateway.saude.sigtap.cron", name = "enabled", havingValue = "true")
public class SigtapSchedulingConfig {
}
