package com.payflow.gateway.metrics;

import io.micrometer.core.instrument.Clock;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import java.util.Properties;
import org.springframework.boot.actuate.metrics.export.prometheus.PrometheusScrapeEndpoint;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Явно піднімає {@link PrometheusMeterRegistry} і сам scrape-ендпоінт
 * {@code /actuator/prometheus}.
 *
 * <p>Навіщо руками, а не покластись на автоконфіг: у цій зв'язці (Spring Boot 3.5
 * + Micrometer 1.15) {@code PrometheusMetricsExportAutoConfiguration} не
 * спрацьовує - {@code PrometheusMeterRegistry} не створюється, Micrometer
 * відкочується на {@code SimpleMeterRegistry}, а scrape-ендпоінт не
 * реєструється, тож {@code /actuator/prometheus} віддає 404 і Grafana-дашборд
 * (стадія 7) лишається без джерела даних. Усі {@code @Bean} тут з
 * {@link ConditionalOnMissingBean}, тож там, де автоконфіг таки відпрацює
 * (інша версія / інше оточення), цей клас нічого не робить.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(PrometheusMeterRegistry.class)
public class PrometheusEndpointConfig {

    @Bean
    @ConditionalOnMissingBean
    PrometheusRegistry prometheusRegistry() {
        return new PrometheusRegistry();
    }

    @Bean
    @Primary
    @ConditionalOnMissingBean
    PrometheusMeterRegistry prometheusMeterRegistry(PrometheusRegistry prometheusRegistry, Clock clock) {
        return new PrometheusMeterRegistry(PrometheusConfig.DEFAULT, prometheusRegistry, clock);
    }

    @Bean
    @ConditionalOnMissingBean
    PrometheusScrapeEndpoint prometheusScrapeEndpoint(PrometheusRegistry prometheusRegistry) {
        return new PrometheusScrapeEndpoint(prometheusRegistry, new Properties());
    }
}
