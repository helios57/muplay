package io.github.helios57.muplay.ui.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** The binaural generator's list of profiles; a top-level tab. */
@Serializable
data object WavesRoute : NavKey

/** One binaural profile open for editing. */
@Serializable
data class WaveEditorRoute(val profileId: String) : NavKey
