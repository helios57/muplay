package app.muplay.integrations

import javax.inject.Qualifier

/**
 * The clock this plan's code reads time from.
 *
 * Qualified, because Plan 3's `:core:media` provides an **unqualified** `@Singleton
 * java.time.Clock` and two unqualified bindings of one type is a Hilt build failure. This plan
 * depends on Plan 2, not on Plan 3, and must build whether or not Plan 3 has landed — a qualifier
 * costs one annotation and removes the ordering dependency entirely.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IntegrationsClock
