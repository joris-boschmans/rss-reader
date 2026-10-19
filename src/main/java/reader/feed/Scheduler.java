package reader.feed;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The quiet part: feeds are checked on a slow clock in the background, so the app is
 * never waiting on the network when you open it, and never asking you to pull to refresh.
 */
public final class Scheduler implements AutoCloseable {

    public static final Duration INTERVAL = Duration.ofMinutes(30);
    private static final Duration FIRST_RUN = Duration.ofSeconds(5);
    private static final Duration SWEEP_INTERVAL = Duration.ofHours(6);

    private final Refresher refresher;
    private final Sweeper sweeper;
    private final ScheduledExecutorService clock = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "clock");
        t.setDaemon(true);
        return t;
    });

    private volatile Instant nextCheck = Instant.now().plus(FIRST_RUN);

    public Scheduler(Refresher refresher, Sweeper sweeper) {
        this.refresher = refresher;
        this.sweeper = sweeper;
    }

    public void start() {
        clock.scheduleAtFixedRate(() -> {
            nextCheck = Instant.now().plus(INTERVAL);
            refresher.startInBackground();
        }, FIRST_RUN.toSeconds(), INTERVAL.toSeconds(), TimeUnit.SECONDS);

        clock.scheduleAtFixedRate(sweeper::sweep,
                SWEEP_INTERVAL.toSeconds(), SWEEP_INTERVAL.toSeconds(), TimeUnit.SECONDS);
    }

    /** Used by the done page, which tells you when to expect the next check. */
    public Instant nextCheck() {
        return nextCheck;
    }

    @Override
    public void close() {
        clock.shutdownNow();
    }
}
