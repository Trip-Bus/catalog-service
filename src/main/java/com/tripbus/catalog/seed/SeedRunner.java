package com.tripbus.catalog.seed;

import com.tripbus.catalog.common.redis.LeaderLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Ejecuta el seed al arrancar (si SEED_ON_STARTUP=true) y cada madrugada para mantener la
 * ventana de días de viajes. Con varias réplicas solo una lo ejecuta (LeaderLock).
 */
@Component
public class SeedRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedRunner.class);

    private final SeedService seedService;
    private final LeaderLock leaderLock;
    private final boolean onStartup;

    public SeedRunner(SeedService seedService, LeaderLock leaderLock,
                      @Value("${tripbus.seed.on-startup:true}") boolean onStartup) {
        this.seedService = seedService;
        this.leaderLock = leaderLock;
        this.onStartup = onStartup;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seedOnStartup() {
        if (onStartup) {
            runSeed();
        }
    }

    @Scheduled(cron = "0 5 0 * * *", zone = "America/Bogota")
    public void seedDaily() {
        runSeed();
    }

    private void runSeed() {
        boolean ran = leaderLock.runIfLeader("catalog-seed", Duration.ofSeconds(30), seedService::seed);
        if (!ran) {
            log.info("Seed omitido: otra réplica lo está ejecutando o Redis no está disponible");
        }
    }
}
