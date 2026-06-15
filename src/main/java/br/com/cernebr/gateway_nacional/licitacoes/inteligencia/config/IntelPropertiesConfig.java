package br.com.cernebr.gateway_nacional.licitacoes.inteligencia.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registra {@link IntelProperties} como bean independente da flag {@code enabled}
 * — permite que rotas de status/health leiam a configuração mesmo com o módulo
 * desligado, sem instanciar o resto da pilha Postgres.
 *
 * <p>Mesma separação adotada no SIGTAP ({@code SigtapPropertiesConfig}): as
 * propriedades são sempre bindáveis; o {@code IntelDataSourceConfig} é que fica
 * condicional ao flag.</p>
 */
@Configuration
@EnableConfigurationProperties(IntelProperties.class)
public class IntelPropertiesConfig {
}
