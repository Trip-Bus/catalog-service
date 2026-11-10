package com.tripbus.catalog.seed;

import com.tripbus.catalog.common.redis.LeaderLock;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** El seed corre al arrancar (si está activado) y cada madrugada, siempre a través del LeaderLock. */
class SeedRunnerTest {

    private final SeedService seedService = mock(SeedService.class);
    private final LeaderLock leaderLock = mock(LeaderLock.class);

    @Test
    void alArrancarCorreElSeedComoLider() {
        when(leaderLock.runIfLeader(eq("catalog-seed"), any(Duration.class), any(Runnable.class))).thenReturn(true);
        new SeedRunner(seedService, leaderLock, true).seedOnStartup();

        ArgumentCaptor<Runnable> job = ArgumentCaptor.forClass(Runnable.class);
        verify(leaderLock).runIfLeader(eq("catalog-seed"), eq(Duration.ofSeconds(30)), job.capture());
        job.getValue().run();
        verify(seedService).seed();
    }

    @Test
    void desactivadoNoCorreAlArrancar() {
        new SeedRunner(seedService, leaderLock, false).seedOnStartup();
        verify(leaderLock, never()).runIfLeader(any(), any(), any());
    }

    @Test
    void elJobDiarioCorreAunqueOtraReplicaTengaElLock() {
        when(leaderLock.runIfLeader(any(), any(), any())).thenReturn(false);
        new SeedRunner(seedService, leaderLock, false).seedDaily();
        verify(leaderLock).runIfLeader(eq("catalog-seed"), any(Duration.class), any(Runnable.class));
        verify(seedService, never()).seed();
    }
}
