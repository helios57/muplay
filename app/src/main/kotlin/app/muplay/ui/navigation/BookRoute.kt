package app.muplay.ui.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * One book's detail screen. [bookId] is the album id the book is stored as.
 *
 * The id is read off this key at the `entry<BookRoute> { route -> ... }` call site and passed to
 * `BookScreen` as an ordinary parameter -- **not** through a `SavedStateHandle`. Navigation 3
 * populates no argument from a key's own properties; `AlbumViewModel`'s KDoc carries the device
 * transcript of the crash that established it.
 */
@Serializable
data class BookRoute(val bookId: String) : NavKey
