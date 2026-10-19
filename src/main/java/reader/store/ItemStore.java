package reader.store;

import reader.model.Item;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class ItemStore {

    /** A post as it arrives from a feed, already sanitised. */
    public record Incoming(
            String guid, String url, String title, String author,
            Instant publishedAt, String contentHtml, String excerpt) {
    }

    private static final String SELECT =
            "SELECT i.id, i.feed_id, i.guid, i.url, i.title, i.author, i.published_at, "
                    + "i.content_html, i.excerpt, i.fetched_at, i.read_at, i.saved_at, "
                    + "f.title AS feed_title, f.site_url AS feed_site "
                    + "FROM item i JOIN feed f ON f.id = i.feed_id ";

    private final Db db;

    public ItemStore(Db db) {
        this.db = db;
    }

    /**
     * Inserts a post the first time it is seen and does nothing on later sightings,
     * so re-reading an unchanged feed adds no rows.
     *
     * @return true if this was new
     */
    public boolean insertIfNew(long feedId, Incoming in) {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO item(feed_id,guid,url,title,author,published_at,content_html,excerpt,fetched_at) "
                            + "VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(feed_id,guid) DO NOTHING")) {
                ps.setLong(1, feedId);
                ps.setString(2, in.guid());
                ps.setString(3, in.url());
                ps.setString(4, in.title());
                ps.setString(5, in.author());
                Db.setInstant(ps, 6, in.publishedAt());
                ps.setString(7, in.contentHtml());
                ps.setString(8, in.excerpt());
                ps.setLong(9, Instant.now().toEpochMilli());
                return ps.executeUpdate() > 0;
            }
        });
    }

    public int countUnread() {
        return db.with(c -> {
            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM item WHERE read_at IS NULL")) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        });
    }

    /** Unread posts in reading order — the pool a sitting is drawn from. */
    public List<Long> unreadIds(int limit, boolean newestFirst) {
        return db.with(c -> {
            String dir = newestFirst ? "DESC" : "ASC";
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id FROM item WHERE read_at IS NULL "
                            + "ORDER BY COALESCE(published_at, fetched_at) " + dir + ", id " + dir + " LIMIT ?")) {
                ps.setInt(1, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    List<Long> ids = new ArrayList<>();
                    while (rs.next()) ids.add(rs.getLong(1));
                    return ids;
                }
            }
        });
    }

    public Optional<Item> byId(long id) {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(SELECT + "WHERE i.id=?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(read(rs)) : Optional.<Item>empty();
                }
            }
        });
    }

    public void markRead(long id) {
        setTimestamp("read_at", id, Instant.now());
    }

    public void clearRead(long id) {
        setTimestamp("read_at", id, null);
    }

    public void setSaved(long id, boolean saved) {
        setTimestamp("saved_at", id, saved ? Instant.now() : null);
    }

    public List<Item> saved() {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    SELECT + "WHERE i.saved_at IS NOT NULL ORDER BY i.saved_at DESC");
                 ResultSet rs = ps.executeQuery()) {
                List<Item> out = new ArrayList<>();
                while (rs.next()) out.add(read(rs));
                return out;
            }
        });
    }

    /**
     * Forgets unread posts older than the cutoff. Saved posts survive, and so does
     * anything caught up in a sitting that is still open.
     */
    public int deleteForgettable(Instant cutoff) {
        if (cutoff == null) return 0;
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM item WHERE read_at IS NULL AND saved_at IS NULL "
                            + "AND COALESCE(published_at, fetched_at) < ? "
                            + "AND id NOT IN (SELECT si.item_id FROM session_item si "
                            + "JOIN session s ON s.id = si.session_id WHERE s.finished_at IS NULL)")) {
                ps.setLong(1, cutoff.toEpochMilli());
                return ps.executeUpdate();
            }
        });
    }

    private void setTimestamp(String column, long id, Instant value) {
        db.run(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE item SET " + column + "=? WHERE id=?")) {
                Db.setInstant(ps, 1, value);
                ps.setLong(2, id);
                ps.executeUpdate();
            }
        });
    }

    static Item read(ResultSet rs) throws SQLException {
        return new Item(
                rs.getLong("id"),
                rs.getLong("feed_id"),
                rs.getString("guid"),
                rs.getString("url"),
                rs.getString("title"),
                rs.getString("author"),
                Db.instant(rs, "published_at"),
                rs.getString("content_html"),
                rs.getString("excerpt"),
                Db.instant(rs, "fetched_at"),
                Db.instant(rs, "read_at"),
                Db.instant(rs, "saved_at"),
                rs.getString("feed_title"),
                rs.getString("feed_site"));
    }

    static String selectClause() {
        return SELECT;
    }
}
