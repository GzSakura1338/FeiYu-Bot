package dafeiyu.db;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

public final class Migrations {

    private static final List<String> STEPS = List.of(
            """
            CREATE TABLE settings (
                key        TEXT PRIMARY KEY,
                value      TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            );

            CREATE TABLE ws_profiles (
                id         TEXT PRIMARY KEY,
                name       TEXT NOT NULL,
                host       TEXT NOT NULL,
                port       INTEGER NOT NULL,
                path       TEXT NOT NULL,
                token      TEXT NOT NULL DEFAULT '',
                sort       INTEGER NOT NULL,
                created_at INTEGER NOT NULL
            );

            CREATE TABLE model_profiles (
                id            TEXT PRIMARY KEY,
                name          TEXT NOT NULL,
                protocol      TEXT NOT NULL DEFAULT 'openai-chat',
                base_url      TEXT NOT NULL,
                api_key       TEXT NOT NULL DEFAULT '',
                model         TEXT NOT NULL,
                system_prompt TEXT NOT NULL DEFAULT '',
                temperature   REAL,
                max_tokens    INTEGER NOT NULL DEFAULT 0,
                history_turns INTEGER NOT NULL DEFAULT 10,
                sort          INTEGER NOT NULL,
                created_at    INTEGER NOT NULL
            );

            CREATE TABLE messages (
                id           INTEGER PRIMARY KEY AUTOINCREMENT,
                time         INTEGER NOT NULL,
                direction    TEXT NOT NULL CHECK (direction IN ('in', 'out')),
                message_type TEXT NOT NULL,
                group_id     TEXT,
                user_id      TEXT NOT NULL,
                sender_name  TEXT NOT NULL DEFAULT '',
                message_id   TEXT,
                content      TEXT NOT NULL
            );
            CREATE INDEX idx_messages_time ON messages (time);
            CREATE INDEX idx_messages_group ON messages (group_id, time);

            CREATE TABLE conversation_turns (
                id         INTEGER PRIMARY KEY AUTOINCREMENT,
                session    TEXT NOT NULL,
                role       TEXT NOT NULL CHECK (role IN ('user', 'assistant')),
                content    TEXT NOT NULL,
                created_at INTEGER NOT NULL
            );
            CREATE INDEX idx_turns_session ON conversation_turns (session, id)
            """,
            """
            ALTER TABLE model_profiles ADD COLUMN context_window INTEGER;
            ALTER TABLE model_profiles DROP COLUMN history_turns;
            ALTER TABLE conversation_turns ADD COLUMN reasoning TEXT NOT NULL DEFAULT '';

            CREATE TABLE conversation_sessions (
                session         TEXT PRIMARY KEY,
                summary         TEXT,
                recent          TEXT,
                measured_tokens INTEGER NOT NULL DEFAULT 0,
                compactions     INTEGER NOT NULL DEFAULT 0,
                updated_at      INTEGER NOT NULL
            )
            """,
            """
            CREATE TABLE personas (
                id          TEXT PRIMARY KEY,
                name        TEXT NOT NULL,
                description TEXT NOT NULL DEFAULT '',
                avatar      TEXT NOT NULL DEFAULT '',
                prompt      TEXT NOT NULL DEFAULT '',
                sort        INTEGER NOT NULL,
                created_at  INTEGER NOT NULL
            );
            INSERT INTO personas (id, name, description, avatar, prompt, sort, created_at)
                SELECT id, name || ' 的人格', '从模型配置迁移过来的系统提示词', '', system_prompt, sort, created_at
                FROM model_profiles WHERE system_prompt <> '';
            INSERT INTO settings (key, value, updated_at)
                SELECT 'agent.persona', id, created_at FROM personas ORDER BY sort LIMIT 1;
            ALTER TABLE model_profiles DROP COLUMN system_prompt;
            ALTER TABLE model_profiles ADD COLUMN vision INTEGER
            """,
            """
            ALTER TABLE model_profiles ADD COLUMN system_role INTEGER;
            ALTER TABLE personas DROP COLUMN description
            """,
            """
            CREATE TABLE conversation_turns_v5 (
                id           INTEGER PRIMARY KEY AUTOINCREMENT,
                session      TEXT NOT NULL,
                role         TEXT NOT NULL CHECK (role IN ('user', 'assistant', 'tool')),
                content      TEXT NOT NULL,
                reasoning    TEXT NOT NULL DEFAULT '',
                tool_calls   TEXT NOT NULL DEFAULT '',
                tool_call_id TEXT NOT NULL DEFAULT '',
                tool_name    TEXT NOT NULL DEFAULT '',
                tool_error   INTEGER NOT NULL DEFAULT 0,
                created_at   INTEGER NOT NULL
            );
            INSERT INTO conversation_turns_v5 (id, session, role, content, reasoning, created_at)
                SELECT id, session, role, content, reasoning, created_at FROM conversation_turns;
            DROP TABLE conversation_turns;
            ALTER TABLE conversation_turns_v5 RENAME TO conversation_turns;
            CREATE INDEX idx_turns_session ON conversation_turns (session, id);
            ALTER TABLE conversation_sessions ADD COLUMN measured_by TEXT NOT NULL DEFAULT '';
            ALTER TABLE model_profiles ADD COLUMN tool_call INTEGER
            """,
            """
            CREATE TABLE memories (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                persona_id  TEXT NOT NULL,
                scope       TEXT NOT NULL,
                label       TEXT NOT NULL DEFAULT '',
                content     TEXT NOT NULL,
                source      TEXT NOT NULL DEFAULT '',
                created_at  INTEGER NOT NULL,
                updated_at  INTEGER NOT NULL
            );
            CREATE INDEX idx_memories_scope ON memories (persona_id, scope, updated_at);
            CREATE TABLE memory_checkpoints (
                session      TEXT PRIMARY KEY,
                last_turn_id INTEGER NOT NULL,
                reflected_at INTEGER NOT NULL
            )
            """,
            """
            CREATE TABLE stickers (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                hash        TEXT NOT NULL UNIQUE,
                source      TEXT NOT NULL,
                url         TEXT NOT NULL DEFAULT '',
                file        TEXT NOT NULL DEFAULT '',
                mime        TEXT NOT NULL DEFAULT '',
                description TEXT NOT NULL DEFAULT '',
                status      TEXT NOT NULL DEFAULT 'new',
                hidden      INTEGER NOT NULL DEFAULT 0,
                seen_count  INTEGER NOT NULL DEFAULT 0,
                sent_count  INTEGER NOT NULL DEFAULT 0,
                first_seen  INTEGER NOT NULL,
                last_seen   INTEGER NOT NULL,
                last_sent   INTEGER NOT NULL DEFAULT 0
            );
            CREATE INDEX idx_stickers_status ON stickers (status, last_seen)
            """,
            """
            ALTER TABLE conversation_turns ADD COLUMN attachments TEXT NOT NULL DEFAULT ''
            """,
            """
            CREATE TABLE token_usage (
                id                INTEGER PRIMARY KEY AUTOINCREMENT,
                at                INTEGER NOT NULL,
                day               TEXT NOT NULL,
                purpose           TEXT NOT NULL,
                session           TEXT NOT NULL DEFAULT '',
                model             TEXT NOT NULL DEFAULT '',
                effort            TEXT NOT NULL DEFAULT '',
                prompt_tokens     INTEGER NOT NULL DEFAULT 0,
                completion_tokens INTEGER NOT NULL DEFAULT 0,
                cached_tokens     INTEGER NOT NULL DEFAULT 0,
                latency_ms        INTEGER NOT NULL DEFAULT 0
            );
            CREATE INDEX idx_token_usage_day ON token_usage (day)
            """,
            """
            CREATE TABLE skills (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                persona_id  TEXT NOT NULL,
                name        TEXT NOT NULL,
                description TEXT NOT NULL DEFAULT '',
                content     TEXT NOT NULL,
                source      TEXT NOT NULL DEFAULT '',
                created_at  INTEGER NOT NULL,
                updated_at  INTEGER NOT NULL
            );
            CREATE INDEX idx_skills_persona ON skills (persona_id, updated_at)
            """,
            """
            CREATE TABLE plugin_kv (
                plugin_id   TEXT NOT NULL,
                key         TEXT NOT NULL,
                value       TEXT NOT NULL,
                updated_at  INTEGER NOT NULL,
                PRIMARY KEY (plugin_id, key)
            );
            CREATE TABLE plugin_logs (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                plugin_id   TEXT NOT NULL,
                at          INTEGER NOT NULL,
                level       TEXT NOT NULL,
                text        TEXT NOT NULL
            );
            CREATE INDEX idx_plugin_logs ON plugin_logs (plugin_id, id)
            """,
            """
            CREATE TABLE images (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                file        TEXT NOT NULL,
                url         TEXT NOT NULL,
                session     TEXT NOT NULL,
                created_at  INTEGER NOT NULL
            );
            CREATE INDEX idx_images_file ON images (file)
            """,
            """
            ALTER TABLE memories ADD COLUMN importance INTEGER NOT NULL DEFAULT 0;
            ALTER TABLE memories ADD COLUMN used_count INTEGER NOT NULL DEFAULT 0;
            ALTER TABLE memories ADD COLUMN used_at INTEGER NOT NULL DEFAULT 0;
            ALTER TABLE skills ADD COLUMN importance INTEGER NOT NULL DEFAULT 0;
            ALTER TABLE skills ADD COLUMN used_count INTEGER NOT NULL DEFAULT 0;
            ALTER TABLE skills ADD COLUMN used_at INTEGER NOT NULL DEFAULT 0;
            CREATE TABLE memory_trash (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                persona_id  TEXT NOT NULL,
                kind        TEXT NOT NULL,
                title       TEXT NOT NULL DEFAULT '',
                description TEXT NOT NULL DEFAULT '',
                content     TEXT NOT NULL,
                source      TEXT NOT NULL DEFAULT '',
                created_at  INTEGER NOT NULL,
                removed_at  INTEGER NOT NULL,
                reason      TEXT NOT NULL DEFAULT ''
            );
            CREATE INDEX idx_memory_trash ON memory_trash (persona_id, removed_at)
            """,
            """
            CREATE INDEX idx_messages_message_id ON messages (message_id)
            """,
            """
            CREATE TABLE forwards (
                id          TEXT PRIMARY KEY,
                lines       TEXT NOT NULL,
                created_at  INTEGER NOT NULL
            )
            """
    );

    private Migrations() {}

    static void apply(Connection c, Path file) throws SQLException {
        int version;
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("PRAGMA user_version")) {
            version = rs.next() ? rs.getInt(1) : 0;
        }
        if (version > 0 && version < STEPS.size()) {
            backup(c, file, version);
        }
        for (int i = version; i < STEPS.size(); i++) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                for (String sql : STEPS.get(i).split(";")) {
                    if (!sql.isBlank()) {
                        st.execute(sql);
                    }
                }
                st.execute("PRAGMA user_version = " + (i + 1));
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw new SQLException("schema migration " + (i + 1) + " failed: " + e.getMessage(), e);
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    public static Path backupDir(Path file) {
        return file.resolveSibling("backup");
    }

    private static void backup(Connection c, Path file, int version) throws SQLException {
        Path dir = backupDir(file);
        String name = file.getFileName().toString().replaceFirst("\\.db$", "");
        Path target = dir.resolve(name + "-v" + version + "-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".db");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new SQLException("cannot back up before schema migration: " + e.getMessage(), e);
        }
        try (Statement st = c.createStatement()) {
            st.execute("VACUUM INTO '" + target.toString().replace("'", "''") + "'");
        } catch (SQLException e) {
            throw new SQLException("cannot back up before schema migration to " + target + ": " + e.getMessage(), e);
        }
    }
}
