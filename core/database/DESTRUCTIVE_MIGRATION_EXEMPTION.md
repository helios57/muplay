# Destructive-migration exemption

`DataModule.provideDatabase` (in this module, `app.muplay.database.di`) calls
`.fallbackToDestructiveMigration(dropAllTables = true)`. That call is required
throughout the rest of this pre-release Kotlin rewrite (Plans 2 onward, per
`docs/superpowers/plans/2026-08-22-muplay-kotlin-roadmap.md`): nothing has shipped yet,
every task that adds a table bumps `version` with no `Migration`, and a developer's
device (and the emulator that runs the required Tier 2 gate) has to be allowed to throw
its mirror away and re-sync rather than crash on an unmigrated schema.

`build-logic`'s `VerifyNoDestructiveMigrationTask` treats this file's mere existence as
permission for that call to reach a release build without failing the gate -- and prints
a loud, unmissable warning every single time it does, naming this file. No numbered task
in the current roadmap is yet dedicated to "write the real migrations and ship v1" (the
roadmap lists feature plans, not a release-prep plan), so this file names the *work*,
not a task ID that does not exist yet: replacing this exemption is part of whatever work
first prepares MuPlay for an actual release build.

**What must happen before this file is deleted:**

1. Every table this app has ever shipped with needs a real `androidx.room.migration.Migration`
   object, verified against the schema JSON already exported and committed under
   `core/database/schemas/`.
2. `DataModule.provideDatabase` stops calling `fallbackToDestructiveMigration` and
   installs those `Migration` objects instead (`Room.databaseBuilder(...).addMigrations(...)`).
3. This file is deleted in the same change.

Deleting this file *before* step 1-2 land re-arms the gate immediately (`verifyReleaseNoDestructiveMigration`
fails on the very next run, listing the still-present call), which is the fail-safe
direction: a premature deletion is loudly wrong, a forgotten deletion is loudly
exempted, and only the deliberate pairing -- real migrations landing in the same change
that deletes this file -- ever reaches "gate passes and stays quiet."
