package dossier.db

import dossier.model.AnalysisConfig
import dossier.chem.Chemistry
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.Statement

class Database(private val dbPath: Path) {
    private val writeLock = Any()
    val connection: Connection

    init {
        Files.createDirectories(dbPath.toAbsolutePath().parent)
        System.setProperty("org.sqlite.tmpdir", dbPath.toAbsolutePath().parent.toString())
        connection = DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}")
        connection.autoCommit = false
        connection.autoCommit = true
        connection.createStatement().use { st ->
            st.execute("PRAGMA journal_mode=WAL")
            st.execute("PRAGMA foreign_keys=ON")
            st.execute("PRAGMA busy_timeout=5000")
            st.execute("PRAGMA synchronous=FULL")
        }
        connection.autoCommit = false
        migrate()
    }

    private fun migrate() {
        val ddl = """
        CREATE TABLE IF NOT EXISTS branches(
            id TEXT PRIMARY KEY,
            name TEXT NOT NULL,
            parent_branch_id TEXT,
            forked_from_version_id TEXT,
            created_at TEXT NOT NULL
        );
        CREATE TABLE IF NOT EXISTS versions(
            id TEXT PRIMARY KEY,
            branch_id TEXT NOT NULL REFERENCES branches(id),
            seq INTEGER NOT NULL,
            parent_version_id TEXT REFERENCES versions(id),
            event_type TEXT NOT NULL,
            editor TEXT NOT NULL,
            payload TEXT NOT NULL,
            config_version INTEGER NOT NULL,
            created_at TEXT NOT NULL,
            UNIQUE(branch_id, seq)
        );
        CREATE TABLE IF NOT EXISTS configs(
            version INTEGER PRIMARY KEY,
            config_json TEXT NOT NULL,
            editor TEXT NOT NULL,
            created_at TEXT NOT NULL,
            parent_version INTEGER
        );
        CREATE TABLE IF NOT EXISTS observed_peaks(
            id TEXT PRIMARY KEY,
            polarity TEXT NOT NULL,
            identity_mz REAL NOT NULL,
            first_version_id TEXT NOT NULL,
            UNIQUE(polarity, identity_mz)
        );
        CREATE TABLE IF NOT EXISTS raw_rows(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            import_id TEXT NOT NULL,
            line_no INTEGER NOT NULL,
            content_digest TEXT NOT NULL,
            mz REAL, rt REAL, intensity REAL, polarity TEXT, scan INTEGER,
            peak_id TEXT,
            quarantined INTEGER NOT NULL DEFAULT 0,
            error TEXT,
            raw_line TEXT
        );
        CREATE INDEX IF NOT EXISTS idx_raw_import ON raw_rows(import_id);
        CREATE TABLE IF NOT EXISTS peak_states(
            version_id TEXT NOT NULL REFERENCES versions(id),
            peak_id TEXT NOT NULL REFERENCES observed_peaks(id),
            branch_id TEXT NOT NULL,
            raw_mz REAL NOT NULL,
            calibrated_mz REAL NOT NULL,
            intensity REAL NOT NULL,
            rt REAL NOT NULL,
            polarity TEXT NOT NULL,
            scans_json TEXT NOT NULL,
            status TEXT NOT NULL,
            note TEXT,
            PRIMARY KEY(version_id, peak_id)
        );
        CREATE TABLE IF NOT EXISTS runs(
            id TEXT PRIMARY KEY,
            branch_id TEXT NOT NULL,
            version_id TEXT NOT NULL REFERENCES versions(id),
            config_version INTEGER NOT NULL,
            created_at TEXT NOT NULL
        );
        CREATE TABLE IF NOT EXISTS candidates(
            id TEXT PRIMARY KEY,
            run_id TEXT NOT NULL REFERENCES runs(id),
            branch_id TEXT NOT NULL,
            version_id TEXT NOT NULL,
            config_version INTEGER NOT NULL,
            explanation_json TEXT NOT NULL,
            peak_ids_json TEXT NOT NULL,
            target TEXT NOT NULL,
            monoisotopic_peak_id TEXT NOT NULL,
            polarity TEXT NOT NULL,
            charge INTEGER NOT NULL,
            rt REAL NOT NULL
        );
        CREATE INDEX IF NOT EXISTS idx_cand_version ON candidates(version_id);
        CREATE TABLE IF NOT EXISTS decisions(
            id TEXT PRIMARY KEY,
            branch_id TEXT NOT NULL,
            version_id TEXT NOT NULL UNIQUE REFERENCES versions(id),
            candidate_id TEXT NOT NULL,
            run_id TEXT NOT NULL,
            editor TEXT NOT NULL,
            action TEXT NOT NULL,
            rationale TEXT,
            base_version_id TEXT NOT NULL,
            status TEXT NOT NULL,
            conflict_peak_ids_json TEXT,
            conflict_reason TEXT,
            created_at TEXT NOT NULL
        );
        """.trimIndent()
        tx { conn ->
            conn.createStatement().use { st ->
                ddl.split(";").filter { it.isNotBlank() }.forEach { st.execute(it) }
            }
            val rs = st@ conn.createStatement().executeQuery("SELECT COUNT(*) FROM configs")
            rs.next()
            if (rs.getInt(1) == 0) {
                val now = java.time.Instant.now().toString()
                conn.prepareStatement(
                    "INSERT INTO configs(version, config_json, editor, created_at, parent_version) VALUES(?,?,?,?,NULL)"
                ).use { ps ->
                    ps.setInt(1, 1)
                    ps.setString(2, json.encodeToString(AnalysisConfig.serializer(), Chemistry.defaultConfig()))
                    ps.setString(3, "system")
                    ps.setString(4, now)
                    ps.executeUpdate()
                }
            }
        }
    }

    fun <T> tx(block: (Connection) -> T): T = synchronized(writeLock) {
        try {
            val result = block(connection)
            connection.commit()
            result
        } catch (t: Throwable) {
            try { connection.rollback() } catch (_: Exception) {}
            throw t
        }
    }

    fun <T> readOnly(block: (Connection) -> T): T {
        return synchronized(writeLock) { block(connection) }
    }

    fun close() {
        connection.close()
    }

    companion object {
        val json = kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}

fun ResultSet.string(name: String): String? = getString(name)
fun ResultSet.double(name: String): Double = getDouble(name)
