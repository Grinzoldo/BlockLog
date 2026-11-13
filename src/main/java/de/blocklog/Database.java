package de.blocklog;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Logger;

/** Speichert das Protokoll in einer SQLite-Datei. Schreiben und Lesen laufen nie auf dem Haupt-Thread. */
final class Database {

    private static final String COLUMNS = "time, player, action, world, x, y, z, material, amount, extra";
    private static final String INSERT =
            "INSERT INTO log (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private final Object lock = new Object();
    private final Queue<Entry> queue = new ConcurrentLinkedQueue<>();
    private final Logger logger;
    private Connection connection;

    Database(File file, Logger logger) throws SQLException, ClassNotFoundException {
        this.logger = logger;
        Class.forName("org.sqlite.JDBC");
        connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS log ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "time INTEGER NOT NULL, "
                    + "player TEXT NOT NULL, "
                    + "action INTEGER NOT NULL, "
                    + "world TEXT NOT NULL, "
                    + "x INTEGER NOT NULL, "
                    + "y INTEGER NOT NULL, "
                    + "z INTEGER NOT NULL, "
                    + "material TEXT NOT NULL, "
                    + "amount INTEGER NOT NULL, "
                    + "extra TEXT NOT NULL)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_pos ON log (world, x, y, z)");
        }
    }

    void enqueue(Entry entry) {
        queue.add(entry);
    }

    /** Schreibt alle wartenden Eintraege in die Datei. */
    void flush() {
        synchronized (lock) {
            if (connection == null) {
                return;
            }
            Entry entry = queue.poll();
            if (entry == null) {
                return;
            }
            try (PreparedStatement statement = connection.prepareStatement(INSERT)) {
                connection.setAutoCommit(false);
                while (entry != null) {
                    statement.setLong(1, entry.time());
                    statement.setString(2, entry.player());
                    statement.setInt(3, entry.action().id);
                    statement.setString(4, entry.world());
                    statement.setInt(5, entry.x());
                    statement.setInt(6, entry.y());
                    statement.setInt(7, entry.z());
                    statement.setString(8, entry.material());
                    statement.setInt(9, entry.amount());
                    statement.setString(10, entry.extra());
                    statement.addBatch();
                    entry = queue.poll();
                }
                statement.executeBatch();
                connection.commit();
            } catch (SQLException e) {
                logger.severe("Protokoll konnte nicht gespeichert werden: " + e.getMessage());
                try {
                    connection.rollback();
                } catch (SQLException ignored) {
                    // nichts zu tun
                }
            } finally {
                try {
                    connection.setAutoCommit(true);
                } catch (SQLException ignored) {
                    // nichts zu tun
                }
            }
        }
    }

    /**
     * Verlauf eines einzelnen Blocks: Platzieren, Abbauen und Schilder an (bx, by, bz)
     * sowie Container-Zugriffe an (cx, cy, cz).
     */
    List<Entry> blockHistory(String world, int bx, int by, int bz,
                             int cx, int cy, int cz, int limit) throws SQLException {
        String sql = "SELECT " + COLUMNS + " FROM log WHERE world = ? AND ("
                + "(x = ? AND y = ? AND z = ? AND action IN (0, 1, 4)) OR "
                + "(x = ? AND y = ? AND z = ? AND action IN (2, 3))) "
                + "ORDER BY time DESC, id DESC LIMIT ?";
        return query(sql, world, bx, by, bz, cx, cy, cz, limit);
    }

    /** Alles in einem Wuerfel. */
    List<Entry> areaHistory(String world, int x1, int x2, int y1, int y2, int z1, int z2,
                            int limit) throws SQLException {
        String sql = "SELECT " + COLUMNS + " FROM log WHERE world = ? "
                + "AND x BETWEEN ? AND ? AND y BETWEEN ? AND ? AND z BETWEEN ? AND ? "
                + "ORDER BY time DESC, id DESC LIMIT ?";
        return query(sql, world, x1, x2, y1, y2, z1, z2, limit);
    }

    private List<Entry> query(String sql, Object... params) throws SQLException {
        synchronized (lock) {
            flush();
            List<Entry> result = new ArrayList<>();
            if (connection == null) {
                return result;
            }
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                for (int i = 0; i < params.length; i++) {
                    statement.setObject(i + 1, params[i]);
                }
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        result.add(new Entry(
                                rs.getLong(1),
                                rs.getString(2),
                                LogAction.fromId(rs.getInt(3)),
                                rs.getString(4),
                                rs.getInt(5),
                                rs.getInt(6),
                                rs.getInt(7),
                                rs.getString(8),
                                rs.getInt(9),
                                rs.getString(10)));
                    }
                }
            }
            return result;
        }
    }

    void close() {
        synchronized (lock) {
            flush();
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException ignored) {
                    // nichts zu tun
                }
                connection = null;
            }
        }
    }
}
