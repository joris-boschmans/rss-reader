package reader.store;

import reader.model.Item;
import reader.model.Sitting;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The finite sitting, as a database object rather than a UI convention.
 *
 * <p>Beginning a sitting snapshots N unread posts into {@code session_item}. Posts that
 * arrive while it is running cannot join it. Without that snapshot, "done" would only
 * ever be a scroll position that refills behind you.
 */
public final class SessionStore {

    private static final String SELECT_OPEN =
            "SELECT s.id, s.started_at, s.finished_at, s.position, "
                    + "(SELECT COUNT(*) FROM session_item WHERE session_id = s.id) AS total "
                    + "FROM session s WHERE s.finished_at IS NULL ORDER BY s.id DESC LIMIT 1";

    private final Db db;
    private final ItemStore items;
    private final Settings settings;

    public SessionStore(Db db, ItemStore items, Settings settings) {
        this.db = db;
        this.items = items;
        this.settings = settings;
    }

    public Optional<Sitting> current() {
        return db.with(c -> {
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(SELECT_OPEN)) {
                return rs.next() ? Optional.of(read(rs)) : Optional.<Sitting>empty();
            }
        });
    }

    /** Ends whatever was open and snapshots a fresh sitting. Empty when there is nothing unread. */
    public Optional<Sitting> begin() {
        return db.tx(c -> {
            closeOpen(c);
            List<Long> ids = items.unreadIds(settings.size(), settings.newestFirst());
            if (ids.isEmpty()) return Optional.<Sitting>empty();

            long sessionId;
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO session(started_at, position) VALUES(?,0)", Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, Instant.now().toEpochMilli());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    sessionId = keys.getLong(1);
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO session_item(session_id,item_id,ordinal) VALUES(?,?,?)")) {
                for (int i = 0; i < ids.size(); i++) {
                    ps.setLong(1, sessionId);
                    ps.setLong(2, ids.get(i));
                    ps.setInt(3, i);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return Optional.of(new Sitting(sessionId, Instant.now(), null, 0, ids.size()));
        });
    }

    public Optional<Item> currentItem() {
        Optional<Sitting> s = current();
        if (s.isEmpty()) return Optional.empty();
        return itemAtOrAfter(s.get().id(), s.get().position()).flatMap(items::byId);
    }

    /**
     * Marks the post in front of the reader as read and steps on.
     *
     * @return true when that was the last one and the sitting is now finished
     */
    public boolean advance() {
        return db.tx(c -> {
            Optional<Sitting> maybe = current();
            if (maybe.isEmpty()) return true;
            Sitting s = maybe.get();

            Optional<Long> itemId = itemAtOrAfter(s.id(), s.position());
            if (itemId.isEmpty()) {
                finishIn(c, s.id(), s.total());
                return true;
            }
            items.markRead(itemId.get());

            int next = ordinalOf(s.id(), s.position()) + 1;
            if (next >= s.total()) {
                finishIn(c, s.id(), s.total());
                return true;
            }
            setPosition(c, s.id(), next);
            return false;
        });
    }

    /** Un-marks the previous post and steps back, so a mis-click costs nothing. */
    public void back() {
        db.tx(c -> {
            Optional<Sitting> maybe = current();
            if (maybe.isEmpty()) return null;
            Sitting s = maybe.get();
            if (s.position() <= 0) return null;

            int previous = s.position() - 1;
            itemAtOrAfter(s.id(), previous).ifPresent(items::clearRead);
            setPosition(c, s.id(), previous);
            return null;
        });
    }

    /** Ends the sitting early. Everything not yet passed stays unread. */
    public void finish() {
        db.run(this::closeOpen);
    }

    public void toggleSaveOnCurrent() {
        currentItem().ifPresent(item -> items.setSaved(item.id(), !item.isSaved()));
    }

    // ---- internals ------------------------------------------------------

    private void closeOpen(java.sql.Connection c) throws java.sql.SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE session SET finished_at=? WHERE finished_at IS NULL")) {
            ps.setLong(1, Instant.now().toEpochMilli());
            ps.executeUpdate();
        }
    }

    private void finishIn(java.sql.Connection c, long sessionId, int total) throws java.sql.SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE session SET finished_at=?, position=? WHERE id=?")) {
            ps.setLong(1, Instant.now().toEpochMilli());
            ps.setInt(2, total);
            ps.setLong(3, sessionId);
            ps.executeUpdate();
        }
    }

    private void setPosition(java.sql.Connection c, long sessionId, int position) throws java.sql.SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE session SET position=? WHERE id=?")) {
            ps.setInt(1, position);
            ps.setLong(2, sessionId);
            ps.executeUpdate();
        }
    }

    /**
     * The post at this ordinal, or the next one still present. Removing a feed deletes its
     * posts, which can leave a gap in a running sitting; stepping over it is kinder than failing.
     */
    private Optional<Long> itemAtOrAfter(long sessionId, int ordinal) {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT item_id FROM session_item WHERE session_id=? AND ordinal>=? "
                            + "ORDER BY ordinal LIMIT 1")) {
                ps.setLong(1, sessionId);
                ps.setInt(2, ordinal);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(rs.getLong(1)) : Optional.<Long>empty();
                }
            }
        });
    }

    private int ordinalOf(long sessionId, int from) {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT ordinal FROM session_item WHERE session_id=? AND ordinal>=? "
                            + "ORDER BY ordinal LIMIT 1")) {
                ps.setLong(1, sessionId);
                ps.setInt(2, from);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : from;
                }
            }
        });
    }

    private static Sitting read(ResultSet rs) throws java.sql.SQLException {
        return new Sitting(
                rs.getLong("id"),
                Db.instant(rs, "started_at"),
                Db.instant(rs, "finished_at"),
                rs.getInt("position"),
                rs.getInt("total"));
    }
}
