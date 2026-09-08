# Play Console — Data safety answers

The Data safety form is a declaration that must match the binary. Every answer below is followed by
**what makes it true in code**, so it can be re-checked rather than re-remembered. Re-verify before
each submission: an answer that was true at version 1 and silently stopped being true is exactly the
defect class this project is built against.

## Summary answers

| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **No** |
| Is all user data encrypted in transit? | **Yes** — with one declared exception, below |
| Do you provide a way for users to request data deletion? | **Yes** — uninstalling removes everything; there is no server-side copy to delete |

MuPlay has no backend. Data the user enters stays on the device or goes to a server the user
operates. Under Play's definitions this is **not** collection or sharing, because it is neither
transmitted off the device to the developer nor to a third party the developer chose.

## What makes each answer true

**No collection, no sharing.** There is no analytics, crash-reporting, advertising or attribution
dependency in the project. The version catalogue declares 51 libraries and none of them is one —
this is checkable in `gradle/libs.versions.toml` and worth re-checking whenever a dependency is
added.

**No listening history leaves the device.** The Subsonic protocol offers `scrobble`, `nowPlaying`,
`savePlayQueue` and `createBookmark`. MuPlay declares none of them. The endpoints it declares are
the reads `ping`, `search3`, `getMusicFolders`, `getAlbumList2`, `getAlbum`, `getPlaylist`,
`getPlaylists`, `getRandomSongs`, `getScanStatus` and `getOpenSubsonicExtensions`, plus stream and
cover-art URLs, and exactly three writes: `setRating`, `createPlaylist` and `updatePlaylist`.

**The three writes are the thumbs, and they go to the user's own server.** A thumb up or down is
saved as that server's own star rating, and a thumb up adds the track to a `promoted-<username>`
playlist. They are per authenticated user, they carry a track id and a name and nothing else, and
they are issued only when the user taps. Under Play's definitions this is still not collection or
sharing: the destination is a server the user operates, not the developer and not a third party the
developer chose. `LocalOnlyProgressTest` pins the read list and the write list separately, so a
fourth write cannot be added without editing a file that says so.

**Audiobook positions are local-only.** This is a product requirement, not an implementation
detail: positions are written to an app-private table and `ProgressWriter` records in its own
documentation that the upload endpoints are deliberately not called.

**Credentials are encrypted at rest.** Server and integration passwords are sealed with a key held
in the AndroidKeystore, one key per service, and the credential types carry a redacting `toString()`
so a key cannot reach a log line by accident. A convention rule fails the build if anything in the
integrations modules writes to a log at all.

**Encrypted in transit — with one declared exception.** MuPlay refuses cleartext HTTP in release
builds; the allowance exists only in debug builds, for developers testing against a local server,
and is enforced both in code and by a build rule. **If Play asks whether all data is encrypted in
transit, the honest answer accounts for self-hosted users who point the app at a plain-HTTP address
on their own LAN** — decide how to describe that before submitting, and do not claim more than the
binary does.

**Deletion.** Uninstalling removes the app-private database, the cache and the Keystore entries.
`android:allowBackup="false"` means nothing was copied to a cloud backup either.

## Before each submission

1. Re-read Play's current Data safety definitions — they change.
2. Re-run the dependency check above; a new library is the most likely way this becomes untrue.
3. Confirm the Subsonic write list is still exactly `setRating`, `createPlaylist` and
   `updatePlaylist` — `LocalOnlyProgressTest` fails `check` if it is not, but read the list
   yourself before answering a form with it.
4. Confirm the cleartext exception is still debug-only.
