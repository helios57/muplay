package app.muplay.ui.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * The audiobook shelf.
 *
 * `@Serializable` because `rememberNavBackStack` saves keys with `rememberSaveable`.
 *
 * Plan 4 Task 9. Until this key existed the audiobook engine -- `AudiobookRepository`,
 * `BookTimeline`, `ChapterRepository`, `SleepTimerController`, `AudiobookSnapshot`, the resume
 * policy and per-book speed, all merged and all gated -- was reachable from no screen at all. It
 * is pushed from the library screen's `Books` button and from nowhere else.
 */
@Serializable
data object BookshelfRoute : NavKey
