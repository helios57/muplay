package app.muplay.database

import androidx.room.Database
import androidx.room.RoomDatabase
import app.muplay.database.dao.MediaProgressDao
import app.muplay.database.entity.MediaProgressEntity

/**
 * Version 1, and it stays version 1 through this plan: nothing has shipped, so every later task
 * in this plan adds its tables to this same `entities` list rather than writing a migration.
 * `exportSchema = true` (with the schema directory wired up by `muplay.android.room`) is what
 * makes the *first* post-release migration verifiable — a migration test needs the previous
 * schema JSON, and there is no way to recover one that was never exported.
 */
@Database(
  entities = [MediaProgressEntity::class],
  version = 1,
  exportSchema = true,
)
abstract class MuPlayDatabase : RoomDatabase() {

  abstract fun mediaProgressDao(): MediaProgressDao

  companion object {
    const val DATABASE_NAME = "muplay.db"
  }
}
