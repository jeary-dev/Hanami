package dev.jeary.hanami.storage.sql

/** The few SQL differences between the supported databases. */
sealed class SqlDialect(val name: String, val driverClass: String) {

    /** `INSERT ... ` that updates [updateColumns] when [keyColumns] already exist. */
    abstract fun upsert(table: String, columns: List<String>, keyColumns: List<String>, updateColumns: List<String>): String

    /** `INSERT ...` that silently skips rows whose key already exists. */
    abstract fun insertIgnore(table: String, columns: List<String>): String

    protected fun placeholders(columns: List<String>) = columns.joinToString(", ") { "?" }

    data object Sqlite : SqlDialect("SQLite", "org.sqlite.JDBC") {
        override fun upsert(table: String, columns: List<String>, keyColumns: List<String>, updateColumns: List<String>) =
            "INSERT INTO $table (${columns.joinToString()}) VALUES (${placeholders(columns)}) " +
                "ON CONFLICT(${keyColumns.joinToString()}) DO UPDATE SET " +
                updateColumns.joinToString { "$it = excluded.$it" }

        override fun insertIgnore(table: String, columns: List<String>) =
            "INSERT OR IGNORE INTO $table (${columns.joinToString()}) VALUES (${placeholders(columns)})"
    }

    data object MySql : SqlDialect("MySQL", "com.mysql.cj.jdbc.Driver") {
        override fun upsert(table: String, columns: List<String>, keyColumns: List<String>, updateColumns: List<String>) =
            "INSERT INTO $table (${columns.joinToString()}) VALUES (${placeholders(columns)}) " +
                "ON DUPLICATE KEY UPDATE " + updateColumns.joinToString { "$it = VALUES($it)" }

        override fun insertIgnore(table: String, columns: List<String>) =
            "INSERT IGNORE INTO $table (${columns.joinToString()}) VALUES (${placeholders(columns)})"
    }
}
