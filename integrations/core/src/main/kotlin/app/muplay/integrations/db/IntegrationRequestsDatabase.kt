package app.muplay.integrations.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * The integrations' own database. **Not a table in `MuPlayDatabase`**, and the reason is in the
 * plan: this plan is meant to be droppable and reorderable, and a version number claimed inside
 * `MuPlayDatabase` would be a guess about what Plans 3-6 did to it. Version 1, forever, whatever
 * order the plans land in.
 */
@Database(entities = [MediaRequestEntity::class], version = 1)
abstract class IntegrationRequestsDatabase : RoomDatabase() {
  abstract fun requestDao(): MediaRequestDao

  companion object {
    const val DATABASE_NAME: String = "muplay-integration-requests.db"
  }
}
