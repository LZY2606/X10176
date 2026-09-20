package peakdossier.db

object Schema {
    const val SQL = """
    PRAGMA journal_mode=WAL;
    PRAGMA foreign_keys=ON;

    CREATE TABLE IF NOT EXISTS import_run(
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        filename TEXT NOT NULL,
        imported_at TEXT NOT NULL,
        accepted_count INTEGER NOT NULL,
        quarantined_count INTEGER NOT NULL
    );

    CREATE TABLE IF NOT EXISTS scan_row(
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        import_id INTEGER NOT NULL REFERENCES import_run(id),
        line_no INTEGER NOT NULL,
        raw_line TEXT NOT NULL,
        error TEXT NOT NULL
    );

    CREATE TABLE IF NOT EXISTS peak(
        peak_key TEXT PRIMARY KEY,
        polarity TEXT NOT NULL,
        mz TEXT NOT NULL,
        scan TEXT NOT NULL,
        intensity REAL NOT NULL DEFAULT 0,
        first_import_id INTEGER NOT NULL REFERENCES import_run(id),
        first_seen_at TEXT NOT NULL
    );

    CREATE TABLE IF NOT EXISTS version(
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        parent_id INTEGER REFERENCES version(id),
        type TEXT NOT NULL,
        label TEXT NOT NULL,
        editor TEXT NOT NULL,
        created_at TEXT NOT NULL
    );

    CREATE TABLE IF NOT EXISTS ingest_version(
        version_id INTEGER PRIMARY KEY REFERENCES version(id),
        import_id INTEGER NOT NULL REFERENCES import_run(id)
    );

    CREATE TABLE IF NOT EXISTS config_calibration(
        version_id INTEGER PRIMARY KEY REFERENCES version(id),
        slope REAL NOT NULL,
        intercept REAL NOT NULL,
        rms_ppm REAL NOT NULL,
        points_json TEXT NOT NULL
    );

    CREATE TABLE IF NOT EXISTS config_baseline(
        version_id INTEGER PRIMARY KEY REFERENCES version(id),
        noise_floor REAL NOT NULL,
        relative_threshold REAL NOT NULL
    );

    CREATE TABLE IF NOT EXISTS config_rules(
        version_id INTEGER PRIMARY KEY REFERENCES version(id),
        rules_json TEXT NOT NULL,
        rules_version INTEGER NOT NULL
    );

    CREATE TABLE IF NOT EXISTS peak_annotation(
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        version_id INTEGER NOT NULL REFERENCES version(id),
        type TEXT NOT NULL,
        peak_key TEXT NOT NULL,
        reason TEXT NOT NULL,
        replacement_mz REAL,
        replacement_intensity REAL
    );

    CREATE TABLE IF NOT EXISTS adjudication(
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        version_id INTEGER NOT NULL UNIQUE REFERENCES version(id),
        candidate_id TEXT NOT NULL,
        candidate_hash TEXT NOT NULL,
        decision TEXT NOT NULL,
        editor TEXT NOT NULL,
        reason TEXT NOT NULL,
        created_at TEXT NOT NULL,
        UNIQUE(candidate_hash, decision)
    );
    """
}
