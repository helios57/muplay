# MuPlay — Privacy Policy

**Last updated: 26 August 2026**

MuPlay is a free, open-source music and audiobook player for Android. It plays from a
[Navidrome](https://www.navidrome.org/) or Subsonic-compatible server **that you run and that you
name**. It is published under the MIT licence; the complete source is at
`github.com/helios57/muplay`, and every statement below can be checked against it.

## The short version

**MuPlay collects nothing, sends nothing to us, and has no servers of its own.** There is no
account, no telemetry, no analytics, no advertising, and no crash reporting. The only computers
MuPlay talks to are ones whose addresses you typed in yourself.

## What MuPlay stores on your device

| What | Where | Leaves the device? |
|---|---|---|
| Your server address and password | Encrypted with a key held in the Android Keystore | Only as authentication to *your* server |
| A mirror of your library's metadata (album, artist and track names) | App-private database | No |
| **Audiobook positions, per book** | App-private database | **No — never, by design** |
| Playback settings (speed, silence skipping, volume levelling) | App-private database | No |
| Cached audio | App-private cache | No |
| Credentials for optional integrations (Lidarr, Bindery) | Encrypted, one Keystore key per service | Only as authentication to *your* service |

All of it is removed when you uninstall the app. `android:allowBackup` is `false`, so none of it is
copied into a cloud backup.

## What MuPlay sends, and to whom

**To your media server, and only when you have configured one:** requests to list your libraries and
albums, to search, and to stream audio and cover art. These carry your credentials because your
server requires them. MuPlay uses only read-only endpoints — it never writes to your server, never
reports what you played, and never uploads a playback position. The Subsonic `scrobble`,
`nowPlaying` and `savePlayQueue` endpoints exist and MuPlay deliberately does not call them.

**To your local network, only while you are casting:** to play on a Sonos or DLNA speaker, MuPlay
finds speakers by sending a standard discovery message on your local network, and serves the audio
to the speaker from a small server inside the app. That server accepts connections only from your
local network, and each item is reachable only through a random single-use address that is
discarded when you stop casting.

**To an integration you configured, if you configured one:** MuPlay can ask a Lidarr or Bindery
instance you run to fetch music or books you do not have. Nothing is sent unless you set it up and
ask for something.

**To us: nothing.** There is no "us" to send anything to.

## Permissions

- **Internet** and **network state** — to reach the server you named.
- **Foreground service (media playback)** — to keep playing when the screen is off. Android
  requires it to be declared.
- **Notifications** — to show the playback controls you already expect on the lock screen.

MuPlay asks for no location, contacts, microphone, camera, or storage permissions.

## Children

MuPlay is not directed at children and collects nothing from anyone.

## Changes

If this policy changes, the updated version appears here with a new date, and in the repository's
history alongside the code change that prompted it.

## Contact

Open an issue at `github.com/helios57/muplay`.
