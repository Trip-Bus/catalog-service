package com.tripbus.catalog.common.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * HT-10: métricas propias del catálogo (en Prometheus, ver {@link MetricsConfig}):
 * <ul>
 *   <li>{@code tripbus_busquedas_total{cache=hit|miss}}: búsquedas de viajes según si el catálogo salió de la
 *       cache de Redis</li>
 *   <li>{@code tripbus_busqueda_segundos}: Timer de una búsqueda completa (cache o base de datos + disponibilidad)</li>
 *   <li>{@code tripbus_viajes_programados_total}: viajes publicados (programación del operador y seed)</li>
 * </ul>
 */
@Component
public class CatalogMetrics {

    static final String BUSQUEDAS = "tripbus.busquedas";
    static final String BUSQUEDA = "tripbus.busqueda";
    static final String VIAJES_PROGRAMADOS = "tripbus.viajes.programados";

    private final Counter hit;
    private final Counter miss;
    private final Timer busqueda;
    private final Counter viajesProgramados;

    public CatalogMetrics(MeterRegistry registry) {
        this.hit = busquedas(registry, "hit");
        this.miss = busquedas(registry, "miss");
        this.busqueda = Timer.builder(BUSQUEDA)
                .description("Tiempo de una búsqueda de viajes")
                .register(registry);
        this.viajesProgramados = Counter.builder(VIAJES_PROGRAMADOS)
                .description("Viajes publicados (programados por el operador o por el seed)")
                .register(registry);
    }

    /** Sin registro real: para pruebas unitarias. */
    public static CatalogMetrics enMemoria() {
        return new CatalogMetrics(new SimpleMeterRegistry());
    }

    private static Counter busquedas(MeterRegistry registry, String cache) {
        return Counter.builder(BUSQUEDAS).description("Búsquedas de viajes según la cache")
                .tag("cache", cache).register(registry);
    }

    public void busqueda(boolean desdeCache) {
        (desdeCache ? hit : miss).increment();
    }

    public <T> T medirBusqueda(Supplier<T> tarea) {
        return busqueda.record(tarea);
    }

    public void viajesProgramados(int cantidad) {
        viajesProgramados.increment(cantidad);
    }
}
