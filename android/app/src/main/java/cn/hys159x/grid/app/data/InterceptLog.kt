package cn.hys159x.grid.app.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "intercept_log")
data class InterceptLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val time: Long,
    val packageName: String,
    val action: String,
    val nodeDesc: String?,
    /** 点击动作是否派发成功（派发成功≠App响应，但可区分"没点"与"点了没效果"） */
    val clickOk: Boolean = true,
)

@Dao
interface InterceptLogDao {
    // 计划原文为 suspend fun insert；服务侧在 kotlin.concurrent.thread（非挂起上下文）中调用，
    // 编译失败 "Suspend function should be called only from a coroutine"，
    // 改为阻塞式插入——调用点已在后台线程，Room 禁止的只是主线程访问。
    @Insert fun insert(log: InterceptLog)

    @Query("SELECT * FROM intercept_log ORDER BY id DESC LIMIT 200")
    fun recent(): Flow<List<InterceptLog>>

    /** 裁剪：仅保留最近 200 条（隐私声明承诺的实际上限，insert 后调用） */
    @Query("DELETE FROM intercept_log WHERE id NOT IN (SELECT id FROM intercept_log ORDER BY id DESC LIMIT 200)")
    fun trim()

    /** 清空全部日志 */
    @Query("DELETE FROM intercept_log")
    fun clear()
}
