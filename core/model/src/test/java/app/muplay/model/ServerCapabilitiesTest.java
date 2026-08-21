package app.muplay.model;

import static com.google.common.truth.Truth.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.Test;

public class ServerCapabilitiesTest {

  @Test
  public void none_reportsNoExtensionsAndNotOpenSubsonic() {
    assertThat(ServerCapabilities.NONE.isOpenSubsonic()).isFalse();
    assertThat(ServerCapabilities.NONE.supports("songLyrics")).isFalse();
    assertThat(ServerCapabilities.NONE.supports("songLyrics", 1)).isFalse();
  }

  @Test
  public void supports_versionedFormChecksExactVersion() {
    ServerCapabilities caps = new ServerCapabilities(true, Map.of("songLyrics", List.of(1, 2)));

    assertThat(caps.supports("songLyrics")).isTrue();
    assertThat(caps.supports("songLyrics", 1)).isTrue();
    assertThat(caps.supports("songLyrics", 2)).isTrue();
    assertThat(caps.supports("songLyrics", 3)).isFalse();
  }

  @Test
  public void supports_unversionedFormAgreesWithVersionedFormOnEmptyVersionList() {
    // The OpenSubsonic spec sets no minItems on `versions`, so a server may legally advertise an
    // extension with zero versions. supports(name) must not disagree with supports(name, v) for
    // every v in that case — a caller pre-flighting with the unversioned form before making a
    // version-specific call would otherwise get a contradiction (task 5 fix-round review).
    ServerCapabilities caps = new ServerCapabilities(true, Map.of("emptyExtension", List.of()));

    assertThat(caps.supports("emptyExtension")).isFalse();
    assertThat(caps.supports("emptyExtension", 1)).isFalse();
  }

  @Test
  public void supports_unknownExtensionIsFalse() {
    ServerCapabilities caps = new ServerCapabilities(true, Map.of());

    assertThat(caps.supports("unknown")).isFalse();
    assertThat(caps.supports("unknown", 1)).isFalse();
  }
}
