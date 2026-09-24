package cn.hys159x.grid.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [InterceptLog::class], version = 2, exportSchema = false)
abstract class GridDatabase : RoomDatabase() {
    abstract fun interceptLogDao(): InterceptLogDao

    companion object {
        @Volatile private var instance: GridDatabase? = null

        /** 进程级单例：避免每次调用新建 SQLite 连接（fd 泄漏），并保证 Flow 失效通知跨调用点可见 */
        fun get(context: Context): GridDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext, GridDatabase::class.java, "grid.db",
                ).fallbackToDestructiveMigration().build().also { instance = it }
            }
    }
}
