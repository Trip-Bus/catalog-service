package com.tripbus.catalog.common.observability;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** HT-10: nombres y etiquetas de las métricas propias tal como salen en Prometheus. */
class CatalogMetricsTest {

    private PrometheusMeterRegistry registry;
    private CatalogMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        new MetricsConfig().nombresEnEspanol().customize(registry);
        metrics = new CatalogMetrics(registry);
    }

    @Test
    void busquedasPorCache() {
        metrics.busqueda(true);
        metrics.busqueda(false);
        metrics.busqueda(false);

        assertThat(registry.scrape())
                .contains("tripbus_busquedas_total{cache=\"hit\"} 1.0")
                .contains("tripbus_busquedas_total{cache=\"miss\"} 2.0");
    }

    @Test
    void viajesProgramados() {
        metrics.viajesProgramados(3);
        metrics.viajesProgramados(0);

        assertThat(registry.scrape()).contains("tripbus_viajes_programados_total 3.0");
    }

    @Test
    void elTimerDeBusquedaSaleEnSegundos() {
        assertThat(metrics.medirBusqueda(() -> "ok")).isEqualTo("ok");

        assertThat(registry.scrape()).contains("tripbus_busqueda_segundos_count 1")
                .contains("tripbus_busqueda_segundos_sum")
                .doesNotContain("tripbus_busqueda_seconds");
    }

    @Test
    void losDemasTimerSiguenConLaConvencionDeSiempre() {
        registry.timer("http.server.requests").record(() -> {
        });

        assertThat(registry.scrape()).contains("http_server_requests_seconds_count 1");
        assertThat(CatalogMetrics.enMemoria()).isNotNull();
    }
}
