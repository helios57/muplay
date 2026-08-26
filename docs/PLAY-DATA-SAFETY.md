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

**No listening history leaves the device.** The Subsonic protocol offers `scrobble`, `nowPlaying`
and `savePlayQueue`. MuPlay declares none of them; the endpoints it declares are `ping`, `search`,
`getMusicFolders`, `getAlbumList`, `getAlbum`, `getRandomSongs`, `getScanStatus` and
`getOpenSubsonicExtensions`, plus stream and cover-art URLs. All are read-only.

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
3. Confirm no new Subsonic write endpoint has been added.
4. Confirm the cleartext exception is still debug-only.
