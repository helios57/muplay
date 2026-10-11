package io.github.helios57.muplay.binaural

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ProfileLibraryTest {

  @TempDir
  lateinit var dir: File

  private val file get() = File(dir, "binaural.json")
  private var ids = 0

  private fun library() = ProfileLibrary(file, Dispatchers.Unconfined, newId = { "custom-${++ids}" })

  @Test
  fun `a fresh install lists every built-in and nothing else`() = runTest {
    val library = library().apply { load() }

    assertThat(library.document.value.entries.map { it.profile }).isEqualTo(Presets.all)
    assertThat(library.document.value.entries.map { it.origin }).containsOnly(ProfileOrigin.BUILT_IN)
    assertThat(file).doesNotExist()
  }

  @Test
  fun `an edited built-in keeps its place and id, survives a restart, and resets`() = runTest {
    val edited = Presets.concentrate.copy(segments = listOf(Segment(12.0, 160.0, 600.0)))
    library().save(edited)

    val reopened = library().apply { load() }
    val entry = reopened.document.value.find("concentrate")!!
    assertThat(entry.profile).isEqualTo(edited)
    assertThat(entry.origin).isEqualTo(ProfileOrigin.EDITED)
    assertThat(reopened.document.value.entries.map { it.profile.id }).isEqualTo(Presets.all.map { it.id })

    reopened.reset("concentrate")
    assertThat(library().apply { load() }.document.value.find("concentrate")!!.origin).isEqualTo(ProfileOrigin.BUILT_IN)
  }

  @Test
  fun `saving a built-in back to its defaults stores no override`() = runTest {
    val library = library()
    library.save(Presets.sleep.copy(amplitude = 0.05))
    library.save(Presets.sleep)

    assertThat(library.document.value.overrides).isEmpty()
  }

  @Test
  fun `a duplicate is the listener's own, named so it can be told apart, and can be deleted`() = runTest {
    val library = library()
    val copy = library.duplicate("power-nap")!!
    val second = library.duplicate("power-nap")!!

    assertThat(copy.name).isEqualTo("Power nap (copy)")
    assertThat(second.name).isEqualTo("Power nap (copy 2)")
    assertThat(copy.segments).isEqualTo(Presets.powerNap.segments)
    assertThat(library.document.value.find(copy.id)!!.origin).isEqualTo(ProfileOrigin.CUSTOM)

    library.save(copy.copy(name = "Lunch nap"))
    assertThat(library.document.value.custom.map { it.name }).containsExactly("Lunch nap", "Power nap (copy 2)")

    library.delete(copy.id)
    assertThat(library().apply { load() }.document.value.custom.map { it.id }).containsExactly(second.id)
  }

  @Test
  fun `a built-in cannot be deleted, and duplicating nothing returns nothing`() = runTest {
    val library = library()
    library.delete("sleep")

    assertThat(library.document.value.find("sleep")).isNotNull
    assertThat(library.duplicate("missing")).isNull()
  }

  @Test
  fun `an unplayable profile is refused rather than stored`() = runTest {
    assertThatThrownBy { kotlinx.coroutines.runBlocking { library().save(Presets.focus.copy(name = "")) } }
      .isInstanceOf(IllegalArgumentException::class.java)
    assertThat(file).doesNotExist()
  }

  @Test
  fun `the safety note and the volume are remembered`() = runTest {
    library().apply {
      acknowledgeSafety()
      setMasterVolume(1.7)
    }

    val doc = library().apply { load() }.document.value
    assertThat(doc.safetyAcknowledged).isTrue
    assertThat(doc.masterVolume).isEqualTo(1.0)
  }

  @Test
  fun `a file that cannot be read loses the edits, never the feature`() = runTest {
    for (content in listOf("{not json", """{"version":99,"custom":[]}""", """{"custom":[{"id":1}]}""")) {
      file.writeText(content)
      val library = library().apply { load() }
      assertThat(library.document.value.entries.map { it.profile }).describedAs(content).isEqualTo(Presets.all)
    }
  }

  @Test
  fun `a stored profile the rules no longer accept is dropped on reading`() = runTest {
    library().save(Presets.focus.copy(id = "mine", name = "Mine"))
    file.writeText(file.readText().replace("\"name\":\"Mine\"", "\"name\":\"\""))

    assertThat(library().apply { load() }.document.value.custom).isEmpty()
  }

  @Test
  fun `nothing is left behind but the file itself`() = runTest {
    library().acknowledgeSafety()

    assertThat(dir.list()).containsExactly("binaural.json")
  }
}
