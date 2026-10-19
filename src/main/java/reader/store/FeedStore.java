package reader.store;

import reader.model.Feed;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class FeedStore {

    private static final String COLUMNS =
            "id, url, title, site_url, etag, last_modified, last_fetched_at, fetch_error, fail_count, added_at";

    private final Db db;

    public FeedStore(Db db) {
        this.db = db;
    }

    /** Adds a feed, or returns the existing one if that address is already followed. */
    public Feed add(String url, String title, String siteUrl) {
        Optional<Feed> existing = byUrl(url);
        if (existing.isPresent()) return existing.get();
        long id = db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO feed(url,title,site_url,added_at) VALUES(?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, url);
                ps.setString(2, title);
                ps.setString(3, siteUrl);
                ps.setLong(4, Instant.now().toEpochMilli());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    return keys.getLong(1);
                }
            }
        });
        return byId(id).orElseThrow();
    }

    public Optional<Feed> byId(long id) {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM feed WHERE id=?")) {
                ps.setLong(1, id);
                return one(ps);
            }
        });
    }

    public Optional<Feed> byUrl(String url) {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM feed WHERE url=?")) {
                ps.setString(1, url);
                return one(ps);
            }
        });
    }

    public List<Feed> all() {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT " + COLUMNS + " FROM feed ORDER BY LOWER(COALESCE(title,url))");
                 ResultSet rs = ps.executeQuery()) {
                List<Feed> out = new ArrayList<>();
                while (rs.next()) out.add(read(rs));
                return out;
            }
        });
    }

    public int count() {
        return db.with(c -> {
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM feed")) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        });
    }

    public int itemCount(long feedId) {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM item WHERE feed_id=?")) {
                ps.setLong(1, feedId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        });
    }

    public void remove(long id) {
        db.run(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM feed WHERE id=?")) {
                ps.setLong(1, id);
                ps.executeUpdate();
            }
        });
    }

    /** A fetch that produced a feed: clears any error and stores the revalidation tokens. */
    public void recordSuccess(long id, String title, String siteUrl, String etag, String lastModified) {
        db.run(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE feed SET title=COALESCE(NULLIF(?,''),title), "
                            + "site_url=COALESCE(NULLIF(?,''),site_url), etag=?, last_modified=?, "
                            + "last_fetched_at=?, fetch_error=NULL, fail_count=0 WHERE id=?")) {
                ps.setString(1, title == null ? "" : title);
                ps.setString(2, siteUrl == null ? "" : siteUrl);
                ps.setString(3, etag);
                ps.setString(4, lastModified);
                ps.setLong(5, Instant.now().toEpochMilli());
                ps.setLong(6, id);
                ps.executeUpdate();
            }
        });
    }

    /** Nothing changed since last time: still a healthy feed. */
    public void recordUnchanged(long id) {
        db.run(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE feed SET last_fetched_at=?, fetch_error=NULL, fail_count=0 WHERE id=?")) {
                ps.setLong(1, Instant.now().toEpochMilli());
                ps.setLong(2, id);
                ps.executeUpdate();
            }
        });
    }

    /** Records a failure without touching last_fetched_at, which marks the last good contact. */
    public void recordError(long id, String message) {
        db.run(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE feed SET fetch_error=?, fail_count=fail_count+1 WHERE id=?")) {
                ps.setString(1, message == null || message.isBlank() ? "unreachable" : message);
                ps.setLong(2, id);
                ps.executeUpdate();
            }
        });
    }

    private static Optional<Feed> one(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            return rs.next() ? Optional.of(read(rs)) : Optional.empty();
        }
    }

    private static Feed read(ResultSet rs) throws SQLException {
        return new Feed(
                rs.getLong("id"),
                rs.getString("url"),
                rs.getString("title"),
                rs.getString("site_url"),
                rs.getString("etag"),
                rs.getString("last_modified"),
                Db.instant(rs, "last_fetched_at"),
                rs.getString("fetch_error"),
                rs.getInt("fail_count"),
                Db.instant(rs, "added_at"));
    }
}
