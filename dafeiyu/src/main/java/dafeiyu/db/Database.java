package dafeiyu.db;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public final class Database implements AutoCloseable {

    @FunctionalInterface
    public interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    @FunctionalInterface
    public interface Work<T> {
        T run(Connection connection) throws SQLException;
    }

    private final Path file;
    private final Connection connection;
    private final List<String> warnings;

    private Database(Path file, Connection connection, List<String> warnings) {
        this.file = file;
        this.connection = connection;
        this.warnings = List.copyOf(warnings);
    }

    public List<String> warnings() {
        return warnings;
    }

    public static Database open(Path file) {
        Path absolute = file.toAbsolutePath().normalize();
        // 失败路径要能关掉连接：迁移抛异常时若不 close，
        // systemd 反复重启会攒下一堆句柄 + WAL/-shm 不释放，把一次失败放大成崩溃循环。
        Connection connection = null;
        try {
            Files.createDirectories(absolute.getParent());
            connection = DriverManager.getConnection("jdbc:sqlite:" + absolute);
            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA journal_mode = WAL");
                st.execute("PRAGMA synchronous = NORMAL");
                st.execute("PRAGMA foreign_keys = ON");
                st.execute("PRAGMA busy_timeout = 5000");
            }
            List<String> warnings = Migrations.apply(connection, absolute);
            warnings.forEach(w -> System.err.println("WARN " + w));
            return new Database(absolute, connection, warnings);
        } catch (IOException | SQLException e) {
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException ignored) {
                    // 关不掉也没别的办法，别盖住原始错误
                }
            }
            throw new DatabaseException("cannot open database " + absolute + ": " + e.getMessage(), e);
        }
    }

    public Path file() {
        return file;
    }

    public int schemaVersion() {
        return one("PRAGMA user_version", rs -> rs.getInt(1));
    }

    public int update(String sql, Object... params) {
        return run(c -> {
            try (PreparedStatement ps = prepare(c, sql, params)) {
                return ps.executeUpdate();
            }
        });
    }

    public <T> List<T> list(String sql, RowMapper<T> mapper, Object... params) {
        return run(c -> {
            try (PreparedStatement ps = prepare(c, sql, params); ResultSet rs = ps.executeQuery()) {
                List<T> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(mapper.map(rs));
                }
                return rows;
            }
        });
    }

    public <T> T one(String sql, RowMapper<T> mapper, Object... params) {
        List<T> rows = list(sql, mapper, params);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public <T> T transaction(Work<T> work) {
        return run(c -> {
            c.setAutoCommit(false);
            try {
                T result = work.run(c);
                c.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        });
    }

    public static int update(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = prepare(c, sql, params)) {
            return ps.executeUpdate();
        }
    }

    public static <T> List<T> list(Connection c, String sql, RowMapper<T> mapper, Object... params) throws SQLException {
        try (PreparedStatement ps = prepare(c, sql, params); ResultSet rs = ps.executeQuery()) {
            List<T> rows = new ArrayList<>();
            while (rs.next()) {
                rows.add(mapper.map(rs));
            }
            return rows;
        }
    }

    private synchronized <T> T run(Work<T> work) {
        try {
            return work.run(connection);
        } catch (SQLException e) {
            throw new DatabaseException("database error: " + e.getMessage(), e);
        }
    }

    private static PreparedStatement prepare(Connection c, String sql, Object... params) throws SQLException {
        PreparedStatement ps = c.prepareStatement(sql);
        for (int i = 0; i < params.length; i++) {
            ps.setObject(i + 1, params[i]);
        }
        return ps;
    }

    @Override
    public synchronized void close() {
        try {
            connection.close();
        } catch (SQLException ignored) {
        }
    }
}
