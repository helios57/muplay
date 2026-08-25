package app.muplay.database

import app.muplay.model.ScanStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SyncDecisionTest {

  private fun status(scanning: Boolean = false, lastScan: String? = "s1") =
    ScanStatus(isScanning = scanning, scannedCount = 4, lastScan = lastScan)

  @Test
  fun `an unchanged watermark means there is nothing to do`() {
    assertThat(SyncDecision.decide(stored = "s1", status = status(lastScan = "s1")))
      .isEqualTo(SyncDecision.UpToDate)
  }

  @Test
  fun `a moved watermark triggers a reconcile carrying the new value`() {
    assertThat(SyncDecision.decide(stored = "s1", status = status(lastScan = "s2")))
      .isEqualTo(SyncDecision.Reconcile("s2"))
  }

  @Test
  fun `the first ever sync reconciles`() {
    assertThat(SyncDecision.decide(stored = null, status = status(lastScan = "s1")))
      .isEqualTo(SyncDecision.Reconcile("s1"))
  }

  @Test
  fun `a scan in progress is never a reason to reconcile`() {
    // A mid-scan server reports a partially-populated library. Mirroring that and then storing a
    // watermark saying "done" leaves the mirror permanently short of whatever had not been
    // scanned yet -- silently, and with nothing to trigger a retry.
    assertThat(SyncDecision.decide(stored = "s1", status = status(scanning = true, lastScan = "s2")))
      .isEqualTo(SyncDecision.ScanInProgress)
    // Even when the watermark has not moved: "busy" is not "up to date".
    assertThat(SyncDecision.decide(stored = "s1", status = status(scanning = true, lastScan = "s1")))
      .isEqualTo(SyncDecision.ScanInProgress)
  }

  @Test
  fun `a server that reports no lastScan reconciles every time and stores nothing`() {
    // `lastScan` is Navidrome's extension; a plain Subsonic server does not send it. Without it
    // there is no way to tell whether anything changed, so the safe answer is to reconcile -- and
    // to carry a null watermark, so nothing is stored that would later read as "up to date"
    // against a server that never says otherwise.
    val decision = SyncDecision.decide(stored = null, status = status(lastScan = null))

    assertThat(decision).isEqualTo(SyncDecision.Reconcile(null))
  }

  @Test
  fun `a stored watermark does not suppress a reconcile when the server stops reporting one`() {
    assertThat(SyncDecision.decide(stored = "s1", status = status(lastScan = null)))
      .isEqualTo(SyncDecision.Reconcile(null))
  }
}
