package io.github.helios57.muplay.waves

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.helios57.muplay.binaural.ProfileLibrary
import io.github.helios57.muplay.binaural.ProfileOrigin
import io.github.helios57.muplay.binaural.ProfileProblem
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface EditorUiState {
  data object Loading : EditorUiState

  /** The profile was deleted, or never existed: the screen goes back. */
  data object Gone : EditorUiState

  data class Editing(
    val draft: ProfileDraft,
    val origin: ProfileOrigin,
    val problems: List<ProfileProblem>,
  ) : EditorUiState {
    val canSave: Boolean get() = problems.none { it.blocking }
  }
}

@HiltViewModel
class WaveEditorViewModel @Inject constructor(
  private val library: ProfileLibrary,
  private val playback: BinauralPlayback,
) : ViewModel() {

  private val state = MutableStateFlow<EditorUiState>(EditorUiState.Loading)
  val uiState: StateFlow<EditorUiState> = state.asStateFlow()
  private var openedId: String? = null

  /**
   * Called from a `LaunchedEffect(profileId)`, so again on every rotation: a draft already open
   * for this id is kept, because rereading it would throw away what is being typed.
   *
   * That guard is only right while the editor is on screen. This view model is scoped to the
   * activity, not to the navigation entry, so it outlives the editor -- and without [close] the next
   * visit to the same profile was handed the draft from before the last save, still marked as
   * built-in, with no Reset offered.
   */
  fun open(profileId: String) {
    if (openedId == profileId) return
    openedId = profileId
    viewModelScope.launch {
      library.load()
      show(profileId)
    }
  }

  /** The editor is being left for good: the next [open] reads the profile afresh. */
  fun close() {
    openedId = null
    state.value = EditorUiState.Loading
  }

  fun edit(transform: (ProfileDraft) -> ProfileDraft) {
    val current = state.value as? EditorUiState.Editing ?: return
    val draft = transform(current.draft)
    state.value = current.copy(draft = draft, problems = draft.problems())
  }

  /** Plays the draft as it stands, saved or not. */
  fun tryIt() {
    val profile = (state.value as? EditorUiState.Editing)?.takeIf { it.canSave }?.draft?.toProfile() ?: return
    playback.play(profile)
  }

  fun save(onSaved: () -> Unit) {
    val profile = (state.value as? EditorUiState.Editing)?.takeIf { it.canSave }?.draft?.toProfile() ?: return
    viewModelScope.launch {
      library.save(profile)
      playback.update(profile)
      onSaved()
    }
  }

  fun reset() {
    val id = openedId ?: return
    viewModelScope.launch {
      library.reset(id)
      show(id)
    }
  }

  fun duplicate(onDuplicated: (String) -> Unit) {
    val id = openedId ?: return
    viewModelScope.launch { library.duplicate(id)?.let { onDuplicated(it.id) } }
  }

  fun delete(onDeleted: () -> Unit) {
    val id = openedId ?: return
    viewModelScope.launch {
      library.delete(id)
      if ((playback.status.value as? BinauralStatus.Playing)?.profile?.id == id) playback.stop()
      state.value = EditorUiState.Gone
      onDeleted()
    }
  }

  private fun show(id: String) {
    val entry = library.document.value.find(id)
    state.value = if (entry == null) {
      EditorUiState.Gone
    } else {
      val draft = ProfileDraft.of(entry.profile)
      EditorUiState.Editing(draft, entry.origin, draft.problems())
    }
  }
}
