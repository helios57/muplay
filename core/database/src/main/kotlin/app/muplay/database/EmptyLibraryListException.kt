package app.muplay.database

/**
 * Thrown by [LibraryRepository.refreshFromServer] when `getMusicFolders` reports **no** libraries
 * while the local mirror already knows about at least one.
 *
 * This is a deliberate, permanent refusal, not a "detect malformed responses" heuristic that some
 * future fix could relax. `SubsonicClient.getMusicFolders` maps an absent `musicFolders` payload
 * to an empty list rather than throwing (unlike `getScanStatus`/`getAlbum`, which both throw
 * `SubsonicMalformedResponseException` in the equivalent situation) — so an empty response is
 * genuinely ambiguous between "the admin deleted every library" and "a proxy, an outage, or a
 * non-compliant server produced a technically-200 body with nothing in it". This module cannot
 * tell those apart, and the two outcomes are wildly asymmetric: [LibraryEntity.role] is the
 * **only** data in this schema that is not a mirror of the server (see its own kdoc) — the user
 * typed it during setup, nothing can re-derive it, and every browse and shuffle path filters on
 * it. Merging an empty list runs `DELETE FROM libraries WHERE musicFolderId NOT IN ()`, which
 * removes every row — role tags included — and orphans every mirrored album/song/artist beneath
 * them (there are no foreign keys anywhere in this schema).
 *
 * Refusing to merge is therefore the only side of that ambiguity this module is willing to take,
 * permanently: even a *genuine* "every library was deleted on the server" event is left for a
 * later, corroborating sync rather than acted on from a single response. The local mirror simply
 * stays exactly as it was; nothing is deleted, nothing is guessed.
 */
class EmptyLibraryListException :
  IllegalStateException(
    "getMusicFolders reported no libraries, but the local mirror already has at least one with " +
      "user-assigned roles -- refusing to let refreshFromServer delete them (see this " +
      "exception's own kdoc for why this is a permanent refusal, not a heuristic)",
  )
