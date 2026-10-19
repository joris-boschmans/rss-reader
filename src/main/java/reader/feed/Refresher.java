package reader.feed;

import reader.model.Feed;
import reader.store.FeedStore;
import reader.store.ItemStore;
import reader.store.Settings;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Checks feeds. One feed failing never stops the others: each is fetched and parsed in
 * its own try/catch, and a failure is written to the feed row rather than thrown.
 */
public final class Refresher implements AutoCloseable {

    private final Fetcher fetcher;
    private final FeedStore feeds;
    private final ItemStore items;
    private final Settings settings;

    private final ExecutorService pool = Executors.newFixedThreadPool(4, named("check"));
    private final ExecutorService coordinator = Executors.newSingleThreadExecutor(named("checker"));
    private final AtomicBoolean running = new AtomicBoolean(false);

    public Refresher(Fetcher fetcher, FeedStore feeds, ItemStore items, Settings settings) {
        this.fetcher = fetcher;
        this.feeds = feeds;
        this.items = items;
        this.settings = settings;
    }

    public boolean isRunning() {
        return running.get();
    }

    /**
     * Hands the work to the background and returns at once, so a manual check never
     * holds a page open waiting on the slowest feed.
     *
     * @return false when a check is already under way
     */
    public boolean startInBackground() {
        if (!running.compareAndSet(false, true)) return false;
        coordinator.submit(() -> {
            try {
                refreshAll();
            } finally {
                running.set(false);
            }
        });
        return true;
    }

    /** @return how many posts were new */
    public int refreshAll() {
        List<Feed> all = feeds.all();
        int added = 0;
        if (!all.isEmpty()) {
            List<Callable<Integer>> jobs = new ArrayList<>();
            for (Feed f : all) jobs.add(() -> refresh(f));
            try {
                for (Future<Integer> future : pool.invokeAll(jobs)) {
                    try {
                        added += future.get();
                    } catch (Exception e) {
                        // Already recorded against the feed itself.
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        settings.recordCheck(added);
        return added;
    }

    /** @return how many posts this feed contributed */
    public int refresh(Feed feed) {
        try {
            Fetcher.Result r = fetcher.get(feed.url(), feed.etag(), feed.lastModified());
            if (r.unchanged()) {
                feeds.recordUnchanged(feed.id());
                return 0;
            }
            if (!r.ok()) {
                feeds.recordError(feed.id(), "status " + r.status());
                return 0;
            }
            Parser.Parsed parsed = Parser.parse(r.body(), r.contentType());
            int added = 0;
            for (Parser.ParsedItem p : parsed.items()) {
                String base = p.url() != null ? p.url() : feed.url();
                Sanitizer.Clean clean = Sanitizer.clean(p.contentHtml(), base);
                boolean isNew = items.insertIfNew(feed.id(), new ItemStore.Incoming(
                        p.guid(), p.url(), p.title(), p.author(), p.publishedAt(),
                        clean.html(), clean.excerpt()));
                if (isNew) added++;
            }
            feeds.recordSuccess(feed.id(), parsed.title(), parsed.siteUrl(), r.etag(), r.lastModified());
            return added;
        } catch (Exception e) {
            feeds.recordError(feed.id(), describe(e));
            return 0;
        }
    }

    /** Kept for the feed row only; the reader is shown plain language instead. */
    private static String describe(Exception e) {
        String m = e.getMessage();
        return e.getClass().getSimpleName() + (m == null || m.isBlank() ? "" : ": " + m);
    }

    private static java.util.concurrent.ThreadFactory named(String prefix) {
        return r -> {
            Thread t = new Thread(r, prefix);
            t.setDaemon(true);
            return t;
        };
    }

    @Override
    public void close() {
        pool.shutdownNow();
        coordinator.shutdownNow();
    }
}
