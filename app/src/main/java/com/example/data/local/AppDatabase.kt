package com.example.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "cached_games")
data class CachedGameEntity(
    @PrimaryKey val id: String,
    val slug: String,
    val title: String,
    val publisher: String,
    val category: String,
    val description: String,
    val coverImageUrl: String?,
    val screenshotsJoined: String,
    val isLicensed: Boolean,
    val isAvailable: Boolean,
    val maxResolution: String,
    val maxFps: Int,
    val minGpuTier: String,
    val supportsGamepad: Boolean,
    val supportsKbm: Boolean,
    val installedNodesCount: Int,
    val updatedAtMs: Long = System.currentTimeMillis()
)

@Entity(tableName = "touch_control_profiles")
data class TouchControlPlacementEntity(
    @PrimaryKey val controlId: String,
    val label: String,
    val xFraction: Float,
    val yFraction: Float,
    val scale: Float = 1.0f,
    val opacity: Float = 0.72f
)

@Dao
interface GameCacheDao {
    @Query("SELECT * FROM cached_games ORDER BY title ASC")
    fun observeGames(): Flow<List<CachedGameEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGames(games: List<CachedGameEntity>)

    @Query("DELETE FROM cached_games")
    suspend fun clearGames()
}

@Dao
interface TouchControlDao {
    @Query("SELECT * FROM touch_control_profiles")
    fun observePlacements(): Flow<List<TouchControlPlacementEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlacements(placements: List<TouchControlPlacementEntity>)

    @Query("DELETE FROM touch_control_profiles")
    suspend fun resetPlacements()
}

@Database(
    entities = [CachedGameEntity::class, TouchControlPlacementEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun gameCacheDao(): GameCacheDao
    abstract fun touchControlDao(): TouchControlDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "shubhankar_cloud_db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
