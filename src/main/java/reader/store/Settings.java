package reader.store;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;

/**
 * The three settings, plus the handful of remembered flags, as rows in {@code meta}.
 * Every setting has a default that works, so nothing here blocks a first use.
 */
public final class Settings {

    public static final String SIZE = "posts_per_sitting";
    public static final String ORDER = "order";
    public static final String FORGET = "forget_after";

    public static final String LAST_CHECKED_AT = "last_checked_at";
    public static final String LAST_NEW_COUNT = "last_new_count";
    public static final String KEY_HINT_SEEN = "key_hint_seen";

    public static final int DEFAULT_SIZE = 20;
    public static final String NEWEST = "newest";
    public static final String OLDEST = "oldest";
    public static final String NEVER = "never";

    private final Db db;

    public Settings(Db db) {
        this.db = db;
    }

    public int size() {
        int n = parseInt(get(SIZE), DEFAULT_SIZE);
        return (n == 10 || n == 20 || n == 30) ? n : DEFAULT_SIZE;
    }

    public boolean newestFirst() {
        return !OLDEST.equals(get(ORDER));
    }

    public String order() {
        return newestFirst() ? NEWEST : OLDEST;
    }

    /** One of never, 1w, 2w, 1m. */
    public String forget() {
        String v = get(FORGET);
        return switch (v == null ? NEVER : v) {
            case "1w", "2w", "1m" -> v;
            default -> NEVER;
        };
    }

    /** Posts unread and older than this may be forgotten; null means never. */
    public Instant forgetCutoff() {
        Duration d = switch (forget()) {
            case "1w" -> Duration.ofDays(7);
            case "2w" -> Duration.ofDays(14);
            case "1m" -> Duration.ofDays(30);
            default -> null;
        };
        return d == null ? null : Instant.now().minus(d);
    }

    public void save(int size, String order, String forget) {
        put(SIZE, String.valueOf(size == 10 || size == 20 || size == 30 ? size : DEFAULT_SIZE));
        put(ORDER, OLDEST.equals(order) ? OLDEST : NEWEST);
        put(FORGET, switch (forget == null ? NEVER : forget) {
            case "1w", "2w", "1m" -> forget;
            default -> NEVER;
        });
    }

    // ---- Remembered odds and ends ---------------------------------------

    public Instant lastCheckedAt() {
        String v = get(LAST_CHECKED_AT);
        return v == null ? null : Instant.ofEpochMilli(Long.parseLong(v));
    }

    public void recordCheck(int newPosts) {
        put(LAST_CHECKED_AT, String.valueOf(Instant.now().toEpochMilli()));
        put(LAST_NEW_COUNT, String.valueOf(newPosts));
    }

    public int lastNewCount() {
        return parseInt(get(LAST_NEW_COUNT), 0);
    }

    public boolean keyHintSeen() {
        return "1".equals(get(KEY_HINT_SEEN));
    }

    public void markKeyHintSeen() {
        put(KEY_HINT_SEEN, "1");
    }

    // ---- Raw access -----------------------------------------------------

    public String get(String key) {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT value FROM meta WHERE key=?")) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        });
    }

    public void put(String key, String value) {
        db.run(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO meta(key,value) VALUES(?,?) "
                            + "ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
                ps.setString(1, key);
                ps.setString(2, value);
                ps.executeUpdate();
            }
        });
    }

    private static int parseInt(String v, int fallback) {
        try {
            return v == null ? fallback : Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
