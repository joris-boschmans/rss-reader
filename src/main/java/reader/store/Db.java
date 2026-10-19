package reader.store;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;

/**
 * The database: one file, one connection, one guarded writer.
 *
 * <p>Two threads touch it — the request thread and the background pool — and for a
 * single user that is comfortably served by serialising everything on one monitor.
 * It removes the entire class of "database is locked" failures without a pool.
 */
public final class Db implements AutoCloseable {

    /** Bump when schema.sql changes in a way that needs a migration step. */
    public static final int SCHEMA_VERSION = 1;

    public interface SqlFn<T> {
        T apply(Connection c) throws SQLException;
    }

    public interface SqlAction {
        void apply(Connection c) throws SQLException;
    }

    private final Connection conn;
    private final Object lock = new Object();

    public Db(Path file) {
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            conn = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
            try (Statement s = conn.createStatement()) {
                s.execute("PRAGMA journal_mode=WAL");
                s.execute("PRAGMA foreign_keys=ON");
                s.execute("PRAGMA busy_timeout=5000");
                s.execute("PRAGMA synchronous=NORMAL");
            }
            migrate();
        } catch (SQLException | IOException e) {
            throw new IllegalStateException("Could not open the reading file at " + file, e);
        }
    }

    private void migrate() throws SQLException {
        String sql = loadSchema();
        synchronized (lock) {
            // A fresh statement per command: the driver finalises each one after use.
            for (String command : sql.split(";")) {
                if (command.isBlank()) continue;
                try (Statement s = conn.createStatement()) {
                    s.execute(command);
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO meta(key,value) VALUES('schema_version',?) "
                            + "ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
                ps.setString(1, String.valueOf(SCHEMA_VERSION));
                ps.executeUpdate();
            }
        }
    }

    /**
     * Reads the schema with its comments stripped. They are dropped before the file is
     * split on semicolons, so a comment may say whatever it likes, punctuation included.
     */
    private static String loadSchema() {
        try (InputStream in = Db.class.getResourceAsStream("/schema.sql")) {
            if (in == null) throw new IllegalStateException("schema.sql is missing from the build");
            String raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            StringBuilder out = new StringBuilder(raw.length());
            for (String line : raw.split("\n")) {
                if (line.stripLeading().startsWith("--")) continue;
                out.append(line).append('\n');
            }
            return out.toString();
        } catch (IOException e) {
            throw new IllegalStateException("Could not read schema.sql", e);
        }
    }

    /** Runs a unit of work against the single connection. */
    public <T> T with(SqlFn<T> fn) {
        synchronized (lock) {
            try {
                return fn.apply(conn);
            } catch (SQLException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        }
    }

    public void run(SqlAction action) {
        with(c -> {
            action.apply(c);
            return null;
        });
    }

    /** Runs a unit of work in a transaction, rolling back on any failure. */
    public <T> T tx(SqlFn<T> fn) {
        synchronized (lock) {
            try {
                conn.setAutoCommit(false);
                try {
                    T result = fn.apply(conn);
                    conn.commit();
                    return result;
                } catch (SQLException | RuntimeException e) {
                    conn.rollback();
                    throw e;
                } finally {
                    conn.setAutoCommit(true);
                }
            } catch (SQLException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        }
    }

    // ---- Instant <-> epoch millis ---------------------------------------

    public static Instant instant(ResultSet rs, String column) throws SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : Instant.ofEpochMilli(v);
    }

    public static void setInstant(PreparedStatement ps, int index, Instant t) throws SQLException {
        if (t == null) ps.setNull(index, java.sql.Types.INTEGER);
        else ps.setLong(index, t.toEpochMilli());
    }

    @Override
    public void close() {
        try {
            conn.close();
        } catch (SQLException ignored) {
            // Closing on the way out; nothing useful to do about a failure here.
        }
    }
}
