package app.muplay.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * How one book plays, keyed on the **book id** -- the album id of a book in a library the user
 * tagged `AUDIOBOOKS`, or the song id for a loose file that belongs to no album.
 *
 * Deliberately *not* on `media_progress`: that table is keyed on the media id, which is one file,
 * and a thirty-file book would carry thirty independent speeds. See
 * [app.muplay.model.BookSettings]'s own doc.
 *
 * Nothing about position belongs here. Position is `media_progress`'s, at the file's grain, and a
 * `positionMs` column here would create a second answer to "where was I" -- which is the exact
 * inversion spec section 3 exists to prevent, arriving from the other direction.
 */
@Entity(tableName = "book_settings")
data class BookSettingsEntity(
  @PrimaryKey val bookId: String,
  val speed: Float,
  val skipSilence: Boolean,
)
