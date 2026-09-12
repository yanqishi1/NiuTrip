package com.niutrip.app.data.repo

import com.niutrip.app.core.toApiTime
import com.niutrip.app.core.toBeijingDateTime
import com.niutrip.app.data.local.PendingPointDao
import com.niutrip.app.data.remote.ApiException
import com.niutrip.app.data.remote.ApiService
import com.niutrip.app.data.remote.PointIn
import com.niutrip.app.data.remote.PointsIn
import com.niutrip.app.data.remote.apiCall
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

sealed interface FlushResult {
    data class Success(val count: Int) : FlushResult
    data class Failed(val cause: Throwable) : FlushResult
}

data class RecordedTrackPoint(
    val trackId: String,
    val lon: Double,
    val lat: Double,
    val timeMillis: Long,
)

class SyncRepository(private val api: ApiService, private val dao: PendingPointDao) {
    // 每批点上传成功后发出 (trackId, 数量)：详情页订阅后可原地刷新，无需退出重进
    private val _uploads = MutableSharedFlow<Pair<String, Int>>(extraBufferCapacity = 16)
    val uploads: SharedFlow<Pair<String, Int>> = _uploads

    // 轨迹点本地入库后立即发出，不等待网络批量上传；详情页据此实时移动当前位置图标
    private val _recordedPoints = MutableSharedFlow<RecordedTrackPoint>(extraBufferCapacity = 16)
    val recordedPoints: SharedFlow<RecordedTrackPoint> = _recordedPoints

    // 网络恢复/App 启动/采集后可能并发触发补传，串行化避免同一批点重复上传
    private val mutex = Mutex()

    fun notifyPointRecorded(trackId: String, lon: Double, lat: Double, timeMillis: Long) {
        _recordedPoints.tryEmit(RecordedTrackPoint(trackId, lon, lat, timeMillis))
    }

    suspend fun flushOnce(): FlushResult = mutex.withLock {
        val pending = dao.takeFirst(200)
        if (pending.isEmpty()) return@withLock FlushResult.Success(0)
        var uploaded = 0
        var failure: Throwable? = null
        pending.groupBy { it.trackId }.forEach { (trackId, rows) ->
            val points = rows.sortedBy { it.time }.map { row ->
                PointIn(row.pointId, row.lon, row.lat, row.name, row.desc,
                    runCatching { Json.decodeFromString<List<String>>(row.imgs) }.getOrDefault(emptyList()),
                    row.time.toBeijingDateTime().toApiTime(), row.source)
            }
            try {
                apiCall { api.postPoints(trackId, PointsIn(points)) }  // apiCall 把 HttpException 转 ApiException，4xx 才能被识别为永久拒绝
                dao.deleteAll(rows.map { it.id })
                uploaded += rows.size
                _uploads.tryEmit(trackId to rows.size)
            } catch (error: Throwable) {
                if (error is ApiException && error.code in 400..499) {
                    // 服务端永久拒绝（如轨迹已结束）：丢弃这批点，避免永远堵在队头毒化其他轨迹
                    dao.deleteAll(rows.map { it.id })
                } else {
                    failure = error  // 网络类错误：保留待下次触发重试
                }
            }
        }
        if (uploaded == 0 && failure != null) FlushResult.Failed(failure!!) else FlushResult.Success(uploaded)
    }
}
