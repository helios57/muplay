package io.github.helios57.muplay.waves

import io.github.helios57.muplay.binaural.Presets
import io.github.helios57.muplay.binaural.ProfileLibrary
import io.github.helios57.muplay.binaural.ProfileOrigin
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Both view models, over a real [ProfileLibrary] on a temporary file and a recording playback. */
@OptIn(ExperimentalCoroutinesApi::class)
class WavesViewModelsTest {

  @TempDir
  lateinit var dir: File

  private val playback = FakePlayback()
  private lateinit var library: ProfileLibrary

  @BeforeEach
  fun setUp() {
    Dispatchers.setMain(UnconfinedTestDispatcher())
    library = ProfileLibrary(File(dir, "p.json"), Dispatchers.Unconfined)
  }

  @AfterEach
  fun tearDown() = Dispatchers.resetMain()

  @Test
  fun `the list groups programs, steady tones and the listener's own`() = runTest {
    library.duplicate("concentrate")
    val state = WavesViewModel(library, playback).uiState.value

    assertThat(state.programs.map { it.name }).startsWith("Power nap")
    assertThat(state.tones.map { it.name }).contains("Meditate", "Focus", "Concentrate", "Creative", "Sleep", "Deep sleep")
    assertThat(state.yours.map { it.name }).containsExactly("Concentrate (copy)")
    assertThat(state.safetyAcknowledged).isFalse
  }

  @Test
  fun `playing a row plays that profile, as edited, and marks the row`() = runTest {
    library.save(Presets.concentrate.copy(amplitude = 0.05))
    val vm = WavesViewModel(library, playback)

    vm.play("concentrate")

    assertThat(playback.played.single().amplitude).isEqualTo(0.05)
    val state = vm.uiState.value
    assertThat(state.tones.single { it.id == "concentrate" }.playing).isTrue
    assertThat(state.tones.single { it.id == "concentrate" }.edited).isTrue
    assertThat(state.current!!.tones).isEqualTo("Left 158.44 Hz · right 144.98 Hz")
    assertThat(state.current!!.clock).isEqualTo("0:00 of 45:00")
  }

  @Test
  fun `stop stops, and an unknown id plays nothing`() = runTest {
    val vm = WavesViewModel(library, playback)
    vm.play("nope")
    vm.play("sleep")
    vm.stop()

    assertThat(playback.played.map { it.id }).containsExactly("sleep")
    assertThat(vm.uiState.value.current).isNull()
  }

  @Test
  fun `the volume follows the slider live and is remembered only when let go`() = runTest {
    val vm = WavesViewModel(library, playback)
    vm.previewVolume(0.4)
    assertThat(library.document.value.masterVolume).isEqualTo(1.0)

    vm.setVolume(0.3)

    assertThat(playback.volumes).containsSubsequence(0.4, 0.3)
    assertThat(ProfileLibrary(File(dir, "p.json"), Dispatchers.Unconfined).apply { load() }.document.value.masterVolume).isEqualTo(0.3)
  }

  @Test
  fun `a remembered volume is applied when the screen opens`() = runTest {
    library.setMasterVolume(0.2)
    WavesViewModel(library, playback)

    assertThat(playback.volumes).containsExactly(0.2)
  }

  @Test
  fun `the safety note goes once acknowledged`() = runTest {
    val vm = WavesViewModel(library, playback)
    vm.acknowledgeSafety()

    assertThat(vm.uiState.value.safetyAcknowledged).isTrue
  }

  @Test
  fun `a new profile gets a free name and opens for editing`() = runTest {
    val vm = WavesViewModel(library, playback)
    val opened = mutableListOf<String>()
    vm.create { opened += it }
    vm.create { opened += it }

    assertThat(vm.uiState.value.yours.map { it.name }).containsExactly("My profile", "My profile 2")
    assertThat(opened).hasSize(2).doesNotHaveDuplicates()
  }

  @Test
  fun `the editor saves an edited built-in and applies it to the sound if it is playing`() = runTest {
    playback.play(Presets.concentrate)
    val editor = WaveEditorViewModel(library, playback)
    editor.open("concentrate")
    editor.edit { d -> d.withSegment(0) { it.copy(startBeat = "12", endBeat = "12") } }
    var saved = false

    editor.save { saved = true }

    assertThat(saved).isTrue
    assertThat(library.document.value.find("concentrate")!!.origin).isEqualTo(ProfileOrigin.EDITED)
    assertThat(playback.updated.single().segments.single().startBeatHz).isEqualTo(12.0)
  }

  @Test
  fun `the editor will not save or try a draft with a blocking problem`() = runTest {
    val editor = WaveEditorViewModel(library, playback)
    editor.open("focus")
    editor.edit { it.copy(name = "") }
    editor.save { error("saved") }
    editor.tryIt()

    val state = editor.uiState.value as EditorUiState.Editing
    assertThat(state.canSave).isFalse
    assertThat(state.problems.map { it.message }).contains("Give the profile a name.")
    assertThat(playback.played).isEmpty()
  }

  @Test
  fun `try it plays the draft without saving it`() = runTest {
    val editor = WaveEditorViewModel(library, playback)
    editor.open("focus")
    editor.edit { d -> d.withSegment(0) { it.copy(startBeat = "20", endBeat = "20") } }
    editor.tryIt()

    assertThat(playback.played.single().segments.single().startBeatHz).isEqualTo(20.0)
    assertThat(library.document.value.overrides).isEmpty()
  }

  @Test
  fun `reset puts the shipped values back into the open draft`() = runTest {
    library.save(Presets.sleep.copy(amplitude = 0.05))
    val editor = WaveEditorViewModel(library, playback)
    editor.open("sleep")
    assertThat((editor.uiState.value as EditorUiState.Editing).origin).isEqualTo(ProfileOrigin.EDITED)

    editor.reset()

    val state = editor.uiState.value as EditorUiState.Editing
    assertThat(state.origin).isEqualTo(ProfileOrigin.BUILT_IN)
    assertThat(state.draft.amplitude).isEqualTo(Presets.sleep.amplitude)
  }

  @Test
  fun `reopening the same profile keeps what is being typed`() = runTest {
    val editor = WaveEditorViewModel(library, playback)
    editor.open("focus")
    editor.edit { it.copy(name = "Typing") }
    editor.open("focus")

    assertThat((editor.uiState.value as EditorUiState.Editing).draft.name).isEqualTo("Typing")
  }

  @Test
  fun `duplicating opens the copy, deleting the listener's own stops it and leaves`() = runTest {
    val editor = WaveEditorViewModel(library, playback)
    editor.open("power-nap")
    var copyId: String? = null
    editor.duplicate { copyId = it }

    val second = WaveEditorViewModel(library, playback)
    second.open(copyId!!)
    playback.play(library.document.value.find(copyId!!)!!.profile)
    var left = false
    second.delete { left = true }

    assertThat(left).isTrue
    assertThat(playback.stops).isEqualTo(1)
    assertThat(library.document.value.custom).isEmpty()
    assertThat(second.uiState.value).isEqualTo(EditorUiState.Gone)
  }

  @Test
  fun `after a save and a close, the same profile reopens as saved and offers reset`() = runTest {
    // The view model is scoped to the activity, so it is the same instance on the next visit.
    val editor = WaveEditorViewModel(library, playback)
    editor.open("power-nap")
    editor.edit { d -> d.withSegment(0) { it.copy(minutes = "7") } }
    editor.save { editor.close() }

    editor.open("power-nap")

    val state = editor.uiState.value as EditorUiState.Editing
    assertThat(state.origin).isEqualTo(ProfileOrigin.EDITED)
    assertThat(state.draft.segments.first().minutes).isEqualTo("7")
  }

  @Test
  fun `edits abandoned by leaving are not shown on the next visit`() = runTest {
    val editor = WaveEditorViewModel(library, playback)
    editor.open("focus")
    editor.edit { it.copy(name = "Half typed") }
    editor.close()

    editor.open("focus")

    assertThat((editor.uiState.value as EditorUiState.Editing).draft.name).isEqualTo("Focus")
  }

  @Test
  fun `a profile that does not exist is gone, not an empty editor`() = runTest {
    val editor = WaveEditorViewModel(library, playback)
    editor.open("missing")

    assertThat(editor.uiState.value).isEqualTo(EditorUiState.Gone)
  }
}
