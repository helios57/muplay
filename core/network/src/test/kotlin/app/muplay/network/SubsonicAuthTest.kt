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
    // A platform-default charset here is a silent auth failure for real users, and the version of
    // this test that shipped could not detect one: its assertions were `token(x) == token(x)` and a
    // `[0-9a-f]{32}` format match, both true under *any* charset. Changing
    // `StandardCharsets.UTF_8` to `ISO_8859_1` left this whole module green, the canonical-vector
    // test above included -- that vector's password is ASCII, where the two charsets agree. So the
    // one code path that authenticates every request had its encoding asserted by nothing.
    //
    // Both expected digests below were computed outside this codebase (`hashlib.md5` over the
    // UTF-8 bytes), so they are an independent oracle in the same sense as the canonical vector.
    //
    // Two vectors, because they fail an ISO-8859-1 regression for different reasons and the second
    // is the sharper one:
    //
    //  - "pässwörd" is fully representable in ISO-8859-1, so a charset regression produces a
    //    *different valid digest* -- no error, no replacement character, just a token the server
    //    rejects. That is exactly the silent auth failure this test exists for. (ISO-8859-1 would
    //    give 37b1d243086a4192e5624315c92a79ea instead.)
    //  - "Ünïcödé-🎵" carries a character no single-byte charset can represent, which Java's
    //    `String.getBytes` silently replaces with `?` rather than throwing -- also a wrong digest,
    //    also silent.
    assertThat(SubsonicAuth.token("pässwörd", "abc")).isEqualTo("be828ce15c833b89deb9077a760ba944")
    assertThat(SubsonicAuth.token("Ünïcödé-🎵", "abc")).isEqualTo("d3733e64ac021ee39299f274ffe0aebe")
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

    // Positive assertion first, and it is not decoration. `doesNotContainKey` and `noneMatch` are
    // **both true of an empty map**, so on their own this test passed while asserting nothing: with
    // `authParams()` returning `emptyMap()` -- the exact Plan 1 defect -- fourteen tests go red and
    // this security test was not one of them. A negative is only evidence once something positive
    // establishes there was anything to search.
    assertThat(params).containsKeys("u", "t", "s", "v", "c", "f")
    assertThat(params).doesNotContainKey("p")
    assertThat(params.values).noneMatch { it.contains("sesame") }
  }

  // `credentials do not leak the password in toString` used to live here. It moved to
  // `:core:model`'s own `SubsonicCredentialsTest`, and grew three more assertions, because
  // `SubsonicCredentials` is declared in `:core:model` and coverage is measured per module -- a
  // test here exercised the class without contributing anything to the module that owns it, which
  // is how that module came to report 100% coverage with four of its five classes never executed.
}
