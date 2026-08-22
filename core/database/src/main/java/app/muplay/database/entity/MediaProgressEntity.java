package app.muplay.database.entity;

import androidx.room.Entity;
import androidx.room.PrimaryKey;
import javax.annotation.Nonnull;

/**
 * The single source of truth for "where was I in this item".
 *
 * <p>There is exactly one of these tables. Music queues and audiobook queues are two pointer lists
 * over it, so switching from a book to music touches no row here — which is the entire reason a
 * book keeps its exact position across an intervening music session.
 *
 * <p>{@code mediaId} is the server's stable id, never a rowid: a re-scan on the server must not
 * orphan a listener's progress.
 *
 * <p>Nothing about queue membership belongs in this table. If you find yourself adding a
 * {@code queuePosition} or {@code isInQueue} column, the design has been inverted.
 */
@Entity(tableName = "media_progress")
public record MediaProgressEntity(
    // Both nullability annotations are required: javax.annotation.Nonnull satisfies this
    // project's own ArchitectureTest/NullAway convention, but Room's @PrimaryKey non-null
    // validation only recognizes androidx.annotation.NonNull (see gradle/libs.versions.toml's
    // androidxAnnotation entry) — referenced fully-qualified, once, rather than imported, since
    // its simple name (NonNull) differs from javax.annotation.Nonnull's (Nonnull) only by
    // capitalization.
    @PrimaryKey @Nonnull @androidx.annotation.NonNull String mediaId,
    long positionMs,
    boolean isFinished,
    long lastPlayedAt,
    float speed,
    boolean skipSilence,
    float gainDb) {}
