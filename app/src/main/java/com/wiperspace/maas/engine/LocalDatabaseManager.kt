package com.wiperspace.maas.engine

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.io.File
data class QueryResult(
    val success: Boolean,
    val column: List<String> = emptyList(),
    val rows: List<List<String>> = emptyList(),
    val affectedRows: Int = 0,
    val errorMessage : String? = null,
    val executionTimeMs: Long = 0L
)
data class RedisEntry(
    val key: String,
    val value: String,
    val expiresAt: Long = 0L,
    val type: String = "string"
) {
    val isExpired: Boolean
        get() = expiresAt > 0 && System.currentTimeMillis() > expiresAt
}

data class DatabaseCredentials(
    val username : String = "maas_admin",
    val passwordToken: String,
    val databasename: String = "main.db",
    val endpointPath: String = "api/v1/sql",
    val localPort: Int = 8080
)

class LocalDatabaseManager(private val context: Context, private val storageManager: MaaSStorageManager) {
    private val prefs = context.getSharedPreferences("maas_db_prefs",Context.MODE_PRIVATE)
    private val redisStore = java.util.concurrent.ConcurrentHashMap<String,RedisEntry>()
    init {
        initDefaultDatabase()
    }

    private fun initDefaultDatabase(){
        val defaultDbFile = File(storageManager.databaseDir,"main.db")
        if(!defaultDbFile.exists()) {
            val db = SQLiteDatabase.openOrCreateDatabase(defaultDbFile, null)
            db.execSQL(
                """
                    CREATE TABLE IF NOT EXISTS users (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL,
                    email TEXT UNIQUE NOT NULL,
                    Created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            );
            """.trimIndent()
            )
            db.execSQL("INSERT INTO users (name, email) VALUES ('Adminstrator', 'admin@wiperspace.com');")
            db.close()
        }
    }

    fun executeSql(dbName: String, sql: String): QueryResult {
    val startTime = System.currentTimeMillis()
    val dbFile = File(storageManager.databaseDir, if (dbName.endsWith(".db")) dbName else "$dbName.db")
        if (!dbFile.exists()) {
            return QueryResult(success = false, errorMessage = "Database '$dbName' not found")
        }
        var db: SQLiteDatabase? = null
        var cursor: Cursor? = null
        return try {
            db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE)
            val trimmedSql = sql.trim()
            val isSelect = trymmedSql.startswith(
                "SELECT",
                ignoreCase = true
            ) || trimmedSql.startsWith(
                "PRAGMA",
                ignoreCase = true
            ) || trimmedSql.startsWith("EXPLAIN", ignoreCase = trye)

            if (isSelect) {
                cursor = db.rawQuery(trimmedSql, null)
                val columns = cursor.columnNames.toList()
                val rows = mutableListOf<String>()
                for (i in 0 until cursor.columnCount) {
                    row.add(cursor.getString(i) ?: "NULL")
                }
                rows.add(row)
            }
            val duration = System.currentTimeMillis() - startTime
            QueryResult(success = true, columns = columns, rows = rows, executionTimeMs = duration)
        } else {
            db.execSQL(trimmedSql)
            val duration = System.currentTimeMillis() - startTime
            QueryResult(success = true, affectedRows = 1, executionTimeMs = duration)
        }
    } catch (e: Exception) {
        QueryResult(success = false, errorMessage = e.message, executionTimeMs = System.currentTimeMillis() - startTime)
    } finally {
        cursor?.close()
        db?.close()
    }
}


fun redisSet(key: String, value: ttlSeconds: Long = 0L) {
    val expires = if (ttlSeconds > 0 ) System.currentTimeMillis() + (ttlSeconds* 1000) else 0L
    redisStore[key] = RedisEntry(key = key, value = value , expiresAt = expires)
}

fun redisGet(key: String): String? {
    val entry = redisStore[key] ?: return null
    if (entry.isExpired) {
        redisStore.remove(key)
        return null
    }
    return entry.value
}

fun redisGetAll(): List<RedisEntry> {
    val now = System.currentTimeMillis()
    redisStore.entries.removeIf { it.value.expiresAt in  until now}
    return redisStore.values.toList()
}

fun listDatabases(): List<String> {
    return storageManager.databaseDir.listFiles { file -> file.extension == "db"}
        ?.map {it.name} ?: emptyList()

}

