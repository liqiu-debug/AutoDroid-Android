package org.autojs.autodroid.schedule

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 本地定时任务：目标（用例/场景）+ 重复策略 + 飞书通知开关。
 * repeatType: DAILY | WEEKLY | INTERVAL | ONCE
 */
@Entity(tableName = "scheduled_runs")
data class ScheduledRun(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String = "",
    val kind: String = "case",          // case | scenario
    val targetId: Int = 0,
    val targetName: String = "",
    val repeatType: String = "DAILY",   // DAILY | WEEKLY | INTERVAL | ONCE
    val timeOfDayMinutes: Int = 9 * 60, // DAILY/WEEKLY：当日分钟数
    val weekday: Int = 1,               // WEEKLY：1=周一 ... 7=周日
    val intervalMinutes: Int = 60,      // INTERVAL
    val onceAtMillis: Long = 0L,        // ONCE
    val notify: Boolean = true,
    val enabled: Boolean = true,
    val nextRunAt: Long = 0L,
)

@Dao
interface ScheduledRunDao {
    @Query("SELECT * FROM scheduled_runs ORDER BY id DESC")
    fun all(): List<ScheduledRun>

    @Query("SELECT * FROM scheduled_runs WHERE id = :id")
    fun byId(id: Long): ScheduledRun?

    @Query("SELECT * FROM scheduled_runs WHERE enabled = 1")
    fun enabledRuns(): List<ScheduledRun>

    @Insert
    fun insert(run: ScheduledRun): Long

    @Update
    fun update(run: ScheduledRun)

    @Delete
    fun delete(run: ScheduledRun)
}

@Database(entities = [ScheduledRun::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun scheduledRuns(): ScheduledRunDao

    companion object {
        val io: ExecutorService = Executors.newSingleThreadExecutor()

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "autodroid_runner.db",
            ).build().also { instance = it }
        }
    }
}
