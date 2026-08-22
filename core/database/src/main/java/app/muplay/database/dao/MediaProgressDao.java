package app.muplay.database.dao;

import androidx.room.Dao;
import androidx.room.Query;
import androidx.room.Upsert;
import app.muplay.database.entity.MediaProgressEntity;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

@Dao
public interface MediaProgressDao {

  @Upsert
  void upsert(@Nonnull MediaProgressEntity progress);

  @Query("SELECT * FROM media_progress WHERE mediaId = :mediaId")
  @Nullable
  MediaProgressEntity find(@Nonnull String mediaId);

  @Nonnull
  @Query("SELECT * FROM media_progress")
  List<MediaProgressEntity> findAll();

  @Nonnull
  @Query("SELECT * FROM media_progress WHERE isFinished = 0 ORDER BY lastPlayedAt DESC LIMIT :limit")
  List<MediaProgressEntity> recentlyPlayed(int limit);
}
