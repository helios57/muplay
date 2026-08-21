package app.muplay.network;

import static com.google.common.truth.Truth.assertThat;

import app.muplay.model.SubsonicCredentials;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

public class SubsonicAuthTest {

  /** Vector from the Subsonic API documentation: md5("sesame" + "c19b2d") . */
  @Test
  public void token_matchesKnownVector() {
    assertThat(SubsonicAuth.token("sesame", "c19b2d"))
        .isEqualTo("26719a1196d2a940705a59634eb18eab");
  }

  @Test
  public void token_isLowercaseHexOf32Chars() {
    String t = SubsonicAuth.token("hunter2", "abcdef");
    assertThat(t).matches("[0-9a-f]{32}");
  }

  @Test
  public void authParams_identifiesAsMuPlay() {
    Map<String, String> p = paramsWithSalt("abcdef");
    // Navidrome strips OpenSubsonic fields for clients named DSub or SubMusic.
    assertThat(p).containsEntry("c", "MuPlay");
    assertThat(p).containsEntry("v", "1.16.1");
    assertThat(p).containsEntry("f", "json");
  }

  @Test
  public void authParams_neverContainThePassword() {
    Map<String, String> p = paramsWithSalt("abcdef");
    assertThat(p.values()).doesNotContain("sesame");
    assertThat(p.keySet()).doesNotContain("p");
  }

  @Test
  public void authParams_useAFreshSaltEachCall() {
    java.util.List<String> salts = new java.util.ArrayList<>();
    SubsonicAuth auth = new SubsonicAuth();
    for (int i = 0; i < 100; i++) {
      salts.add(auth.authParams(creds(), SubsonicAuth.randomSaltSupplier()).get("s"));
    }
    assertThat(Set.copyOf(salts)).hasSize(100);
  }

  @Test
  public void randomSalt_isAtLeastSixCharacters() {
    // The spec requires a salt of at least six characters.
    for (int i = 0; i < 50; i++) {
      assertThat(SubsonicAuth.randomSaltSupplier().get().length()).isAtLeast(6);
    }
  }

  private static SubsonicCredentials creds() {
    return SubsonicCredentials.create("https://music.example.com", "alice", "sesame");
  }

  private static Map<String, String> paramsWithSalt(String salt) {
    return new SubsonicAuth().authParams(creds(), () -> salt);
  }
}
