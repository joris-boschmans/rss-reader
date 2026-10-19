package reader;

import reader.store.Db;
import reader.store.FeedStore;
import reader.store.ItemStore;
import reader.store.SessionStore;
import reader.store.Settings;

import java.nio.file.Path;
import java.time.Instant;

/** A throwaway reading file with the stores wired over it. */
public final class TestDb implements AutoCloseable {

    public final Path file;
    public final Db db;
    public final Settings settings;
    public final FeedStore feeds;
    public final ItemStore items;
    public final SessionStore sittings;
    public final long feedId;

    public TestDb(Path directory) {
        this.file = directory.resolve("reading.db");
        this.db = new Db(file);
        this.settings = new Settings(db);
        this.feeds = new FeedStore(db);
        this.items = new ItemStore(db);
        this.sittings = new SessionStore(db, items, settings);
        this.feedId = feeds.add("https://example.com/feed", "The Example Review", "https://example.com/").id();
    }

    /** Reopens the same file with fresh objects, the way a restart would. */
    public TestDb reopen() {
        db.close();
        return new TestDb(file.getParent());
    }

    public long addPost(String title, Instant published) {
        return addPost(feedId, title, published);
    }

    public long addPost(long feed, String title, Instant published) {
        items.insertIfNew(feed, new ItemStore.Incoming(
                "guid:" + feed + ":" + title, "https://example.com/" + title.hashCode(),
                title, null, published, "<p>" + title + "</p>", title));
        return db.with(c -> {
            try (var ps = c.prepareStatement("SELECT id FROM item WHERE feed_id=? AND guid=?")) {
                ps.setLong(1, feed);
                ps.setString(2, "guid:" + feed + ":" + title);
                try (var rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : -1L;
                }
            }
        });
    }

    public Instant readAt(long itemId) {
        return items.byId(itemId).orElseThrow().readAt();
    }

    @Override
    public void close() {
        db.close();
    }
}
