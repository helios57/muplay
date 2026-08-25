package app.muplay.media.di

import javax.inject.Qualifier

/**
 * The **streaming** HTTP client, as opposed to `:core:network`'s JSON one.
 *
 * `MediaModule`'s own KDoc argues at length that the two clients are deliberately different — a
 * `callTimeout` is a safety net on a short JSON request and a guaranteed mid-track failure on a
 * body that is legitimately open for four minutes. Until this qualifier existed the type system
 * carried no trace of that argument: both are `okhttp3.Call.Factory`, so the first
 * `@Inject callFactory: Call.Factory` written anywhere in the app would silently be handed the
 * streaming client, and `MediaModuleTest`'s three timeout assertions would be gating a binding
 * that reached somewhere nobody intended.
 *
 * A qualifier makes that a compile-time fact instead of a comment: an unqualified `Call.Factory`
 * request now has no binding at all, and Dagger says so.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MediaHttpClient
