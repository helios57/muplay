package app.muplay.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * [SubsonicCredentials.toString] is the one piece of author-written behaviour in this class: a
 * hand-written override whose only job is to keep a plaintext password out of logs and crash
 * reports, since the `toString()` a `data class` generates would print every constructor property.
 * That makes it a security control, and it is tested here — in the module that owns the class —
 * for the reason [ServerCapabilitiesTest] spells out: coverage is measured per module, so a test
 * living in `:core:network` exercises this class without contributing anything to `:core:model`'s
 * own execution data.
 *
 * That was not a theoretical gap. Before this test existed the redaction was asserted only by
 * `:core:network`'s `SubsonicAuthTest`, `:core:model` reported 100% branch coverage against a
 * green floor while four of its five classes had zero covered lines, and deleting that assertion
 * left both tiers green with the security control silently untested.
 *
 * The last two tests exist so the redaction cannot be "fixed" by breaking the class: an override
 * that returned a constant, or a class that dropped the password field, would pass the first test
 * and fail these.
 */
class SubsonicCredentialsTest {

  private val credentials = SubsonicCredentials("https://music.example", "alice", "sesame")

  @Test
  fun `toString does not leak the password`() {
    assertThat(credentials.toString()).doesNotContain("sesame")
  }

  @Test
  fun `toString still identifies which server and user it is for`() {
    // A redaction that returned a constant would satisfy the test above and make every log line
    // about credentials useless. The point is to hide one field, not the object.
    assertThat(credentials.toString()).contains("https://music.example", "alice")
  }

  @Test
  fun `the password is still carried, only hidden from toString`() {
    assertThat(credentials.password).isEqualTo("sesame")
  }

  @Test
  fun `two credentials differing only by password are not equal`() {
    // Redacting by dropping the field from the class would break authentication silently; this
    // pins the password as part of the value, not merely as a property that happens to exist.
    assertThat(credentials).isNotEqualTo(SubsonicCredentials("https://music.example", "alice", "hunter2"))
  }
}
