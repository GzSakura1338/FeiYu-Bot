package dafeiyu.db;

public final class Settings {

    private final Database db;

    public Settings(Database db) {
        this.db = db;
    }

    public String get(String key) {
        return db.one("SELECT value FROM settings WHERE key = ?", rs -> rs.getString(1), key);
    }

    public void put(String key, String value) {
        db.update("""
                INSERT INTO settings (key, value, updated_at) VALUES (?, ?, ?)
                ON CONFLICT (key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at
                """, key, value, System.currentTimeMillis());
    }
}
