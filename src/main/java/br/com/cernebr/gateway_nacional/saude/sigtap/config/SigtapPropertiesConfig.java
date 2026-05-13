package br.com.cernebr.gateway_nacional.saude.sigtap.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registra {@link SigtapProperties} como bean independente da flag do
 * cron — permite que rotas de status leiam a configuração mesmo com o
 * módulo desligado, sem instanciar o resto da pilha SQLite.
 */
@Configuration
@EnableConfigurationProperties(SigtapProperties.class)
public class SigtapPropertiesConfig {
}
