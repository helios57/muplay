package app.muplay.database

/**
 * Thrown when a repository is asked to talk to the server before any credentials were stored.
 *
 * A distinct type, not a bare `IllegalStateException`, because the UI genuinely has to tell it
 * apart from "the server is unreachable": only this one is fixed by the user entering a URL, and
 * conflating them produces a "check your connection" message on a device that has never been
 * configured.
 */
class NotConfiguredException :
  IllegalStateException("No Subsonic credentials are stored; run the setup flow first")
