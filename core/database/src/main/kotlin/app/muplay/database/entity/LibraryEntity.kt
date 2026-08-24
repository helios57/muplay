package app.muplay.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import app.muplay.model.LibraryRole

/**
 * One Navidrome library (a Subsonic "music folder"), and the role **the user** gave it.
 *
 * [musicFolderId] is the server's own id and the primary key. It is also the only thing that
 * connects a mirrored artist, album or song to a library — no Subsonic response carries a library
 * id, so every mirror row's `libraryId` was stamped from the request that fetched it, and this
 * table is what gives that number meaning.
 *
 * [role] is never derived from [name]. "Hörbücher" is not "Audiobooks", and a wrong guess does
 * not fail loudly — it silently poisons shuffle scope, which is the one thing this application
 * exists to get right.
 */
@Entity(tableName = "libraries")
data class LibraryEntity(
  @PrimaryKey val musicFolderId: Int,
  val name: String,
  val role: LibraryRole,
)
