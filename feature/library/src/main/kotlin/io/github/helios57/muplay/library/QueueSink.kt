package io.github.helios57.muplay.library

import io.github.helios57.muplay.media.QueueEditor
import io.github.helios57.muplay.model.Song

/**
 * The two queue edits every track list in this module offers.
 *
 * **One interface rather than the same two members written into three seams.** An album, a folder
 * and a playlist are three screens over one idea -- a list of songs with a row you can tap -- and
 * the queue is one idea too. [AlbumSource], [FolderSource] and [PlaylistSource] each extend this,
 * so "what a browse screen may do to the queue" has a single definition and a fourth track list
 * added later inherits it rather than re-deciding it.
 *
 * On the seam, like `play` beside it, because [QueueEditor] is built over a `MediaController` bound
 * to the main `Looper` and cannot be constructed on the JVM; this project bans mock frameworks.
 *
 * The two are **separate members and not one with a flag**, for the reason `PlaybackControls` gives
 * at length: a `queue(songs, playNext = true)` would let a screen hand the decision down to the
 * adapter, which is the one layer no test in this module can reach.
 */
interface QueueSink {

  /** Appends to the end of whatever is queued. Never starts or changes what is playing. */
  suspend fun enqueue(songs: List<Song>)

  /** Inserts directly after whatever is playing. Never starts or changes what is playing. */
  suspend fun playNext(songs: List<Song>)
}

/**
 * The one real implementation, delegated to by all three `@Inject` constructors.
 *
 * `object : AlbumSource, QueueSink by QueueEditorSink(editor)` rather than two more overrides in
 * each of them: three copies of a one-line forward are three chances for one of them to forward to
 * the other method, and that swap is invisible in review and green under every coverage gate.
 */
internal class QueueEditorSink(private val editor: QueueEditor) : QueueSink {
  override suspend fun enqueue(songs: List<Song>) = editor.enqueue(songs)
  override suspend fun playNext(songs: List<Song>) = editor.playNext(songs)
}
