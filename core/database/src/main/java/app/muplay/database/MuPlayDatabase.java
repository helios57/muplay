package app.muplay.database;

import androidx.room.Database;
import androidx.room.RoomDatabase;
import app.muplay.database.dao.MediaProgressDao;
import app.muplay.database.entity.MediaProgressEntity;
import javax.annotation.Nonnull;

/**
 * The app's single Room database.
 *
 * <p>Schema version stays at 1 through this plan: nothing has been released yet, so there are no
 * users to migrate, and a later task in this plan is expected to add more entities and simply
 * regenerate the v1 schema rather than write a migration for it. That freedom ends the moment this
 * app ships a release — the version freezes at first release, and every schema change after that
 * point needs a real {@code Migration}, not a version bump in place.
 */
@Database(
    entities = {MediaProgressEntity.class},
    version = 1,
    exportSchema = true)
public abstract class MuPlayDatabase extends RoomDatabase {

  @Nonnull
  public abstract MediaProgressDao mediaProgressDao();
}
