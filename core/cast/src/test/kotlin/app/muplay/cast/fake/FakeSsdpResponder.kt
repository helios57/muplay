package app.muplay.cast.fake

import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * Several UPnP devices answering one M-SEARCH on loopback -- a real `DatagramSocket` speaking real
 * SSDP, not a stub returning a list.
 *
 * The point of the whole class is the word **several**. A discovery test with one device cannot
 * observe deduplication, cannot observe ordering, cannot observe the `MediaServer` being excluded,
 * and cannot observe a device that answers two search targets being collapsed into one entry. All
 * four of those are real defects, and all four are invisible with a single responder.
 *
 * Each [Responder] answers only the search targets it declares, exactly as a real device does.
 */
class FakeSsdpResponder(private val responders: List<Responder>) : Closeable {

  data class Responder(
    val location: String,
    val udn: String,
    /** Every `ST` this device answers. A Sonos answers both `MediaRenderer:1` and `ZonePlayer:1`. */
    val searchTargets: List<String>,
    val server: String = "Linux UPnP/1.0 MuPlayFake/1.0",
  )

  private val socket = DatagramSocket(0, InetAddress.getLoopbackAddress())
  private val received = CopyOnWriteArrayList<String>()

  val endpoint: InetSocketAddress get() = InetSocketAddress(InetAddress.getLoopbackAddress(), socket.localPort)

  /** Every datagram this responder was sent, verbatim -- so a test may assert on the bytes. */
  val searches: List<String> get() = received.toList()

  fun start() {
    thread(isDaemon = true, name = "fake-ssdp") {
      val buffer = ByteArray(4096)
      while (!socket.isClosed) {
        val packet = DatagramPacket(buffer, buffer.size)
        val ok = runCatching { socket.receive(packet); true }.getOrDefault(false)
        if (!ok) continue
        val text = String(packet.data, packet.offset, packet.length, Charsets.US_ASCII)
        received += text

        // A real device answers only if it matches the ST, and answers once per matching ST. A
        // responder that answered everything would hide the filtering this suite has to observe.
        val requested = text.lineSequence().firstOrNull { it.startsWith("ST:") }?.removePrefix("ST:")?.trim()
        responders.forEach { responder ->
          responder.searchTargets.filter { it == requested }.forEach { target ->
            val reply = (
              "HTTP/1.1 200 OK\r\n" +
                "CACHE-CONTROL: max-age=1800\r\n" +
                "EXT:\r\n" +
                "LOCATION: ${responder.location}\r\n" +
                "SERVER: ${responder.server}\r\n" +
                "ST: $target\r\n" +
                "USN: ${responder.udn}::$target\r\n\r\n"
              ).toByteArray(Charsets.US_ASCII)
            runCatching { socket.send(DatagramPacket(reply, reply.size, packet.socketAddress)) }
          }
        }
      }
    }
  }

  override fun close() = socket.close()
}
