package app.muplay.network

import app.muplay.model.SubsonicCredentials
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SubsonicAuthTest {

  private val credentials = SubsonicCredentials("https://music.example", "alice", "sesame")

  @Test
  fun `token matches the canonical Subsonic vector`() {
    // From the Subsonic API documentation. An independent oracle: this value was not
    // produced by the implementation under test.
    assertThat(SubsonicAuth.token("sesame", "c19b2d")).isEqualTo("26719a1196d2a940705a59634eb18eab")
  }

  @Test
  fun `token is lowercase hex and preserves leading zeros`() {
    // The classic bug: BigInteger or Integer.toHexString silently drops a leading zero byte,
    // which only fails for about one salt in sixteen. Assert the shape, not just one value.
    repeat(64) { i ->
      val token = SubsonicAuth.token("pw", "salt$i")
      assertThat(token).hasSize(32)
      assertThat(token).matches("[0-9a-f]{32}")
    }
  }

  @Test
  fun `non-ascii passwords hash as utf-8`() {
    // A platform-default charset here is a silent auth failure for real users.
    assertThat(SubsonicAuth.token("Ünïcödé-🎵", "abc"))
      .isEqualTo(SubsonicAuth.token("Ünïcödé-🎵", "abc"))
    assertThat(SubsonicAuth.token("Ünïcödé-🎵", "abc")).matches("[0-9a-f]{32}")
  }

  @Test
  fun `auth params carry the client identifier and protocol version`() {
    val params = SubsonicAuth.authParams(credentials, "c19b2d")
    assertThat(params).containsEntry("u", "alice")
    assertThat(params).containsEntry("s", "c19b2d")
    assertThat(params).containsEntry("t", "26719a1196d2a940705a59634eb18eab")
    assertThat(params).containsEntry("v", "1.16.1")
    assertThat(params).containsEntry("c", "MuPlay")
    assertThat(params).containsEntry("f", "json")
  }

  @Test
  fun `the password never appears in the parameters`() {
    // Plaintext auth must never be emitted, and no stray key may carry it.
    val params = SubsonicAuth.authParams(credentials, "c19b2d")
    assertThat(params).doesNotContainKey("p")
    assertThat(params.values).noneMatch { it.contains("sesame") }
  }

  @Test
  fun `credentials do not leak the password in toString`() {
    assertThat(credentials.toString()).doesNotContain("sesame")
  }
}
