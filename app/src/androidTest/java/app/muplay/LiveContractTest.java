package app.muplay;

import static com.google.common.truth.Truth.assertThat;

import androidx.test.platform.app.InstrumentationRegistry;
import app.muplay.model.MusicLibrary;
import app.muplay.model.ServerCapabilities;
import app.muplay.model.SubsonicCredentials;
import app.muplay.network.CapabilityNegotiator;
import app.muplay.network.SubsonicClient;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/** Runs against the pinned Navidrome container via adb reverse. */
public class LiveContractTest {

  private static SubsonicClient client() {
    String url =
        InstrumentationRegistry.getArguments()
            .getString("TEST_SERVER_URL", "http://127.0.0.1:4533");
    return SubsonicClient.create(SubsonicCredentials.create(url, "admin", "testpass"));
  }

  @Test
  public void pingsARealNavidrome() throws Exception {
    SubsonicClient.ServerInfo info = client().ping().get(30, TimeUnit.SECONDS);
    assertThat(info.type()).isEqualTo("navidrome");
    assertThat(info.openSubsonic()).isTrue();
  }

  @Test
  public void discoversBothSeededLibraries() throws Exception {
    List<MusicLibrary> libraries = client().getMusicFolders().get(30, TimeUnit.SECONDS);
    assertThat(libraries.stream().map(MusicLibrary::name))
        .containsAtLeast("Music", "Audiobooks");
  }

  @Test
  public void advertisesIndexBasedQueue() throws Exception {
    ServerCapabilities caps =
        new CapabilityNegotiator(client()).negotiate().get(30, TimeUnit.SECONDS);
    assertThat(caps.isOpenSubsonic()).isTrue();
    assertThat(caps.supports("indexBasedQueue", 1)).isTrue();
  }
}
