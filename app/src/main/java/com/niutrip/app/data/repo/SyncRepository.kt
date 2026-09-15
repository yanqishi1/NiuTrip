package com.niutrip.app.data.repo

import com.niutrip.app.core.toApiTime
import com.niutrip.app.core.toBeijingDateTime
import com.niutrip.app.core.businessId
import com.niutrip.app.data.local.PendingPointDao
import com.niutrip.app.data.local.PendingPointEntity
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

sealed interface FlushResult {
    data class Success(val count: Int) : FlushResult
    data class Failed(val cause: Throwable) : FlushResult
}

data class RecordedTrackPoint(
    val trackId: String,
    val pointId: String,
    val lon: Double,
    val lat: Double,
    val timeMillis: Long,
)

class SyncRepository(private val api: ApiService, private val dao: PendingPointDao) {
    // 轨迹点本地入库后立即发出，不等待网络批量上传；详情页据此实时移动当前位置图标
    private val _recordedPoints = MutableSharedFlow<RecordedTrackPoint>(extraBufferCapacity = 16)
    val recordedPoints: SharedFlow<RecordedTrackPoint> = _recordedPoints

    // 网络恢复/App 启动/采集后可能并发触发补传，串行化避免同一批点重复上传
    private val mutex = Mutex()

    fun notifyPointRecorded(point: PendingPointEntity) {
        _recordedPoints.tryEmit(point.toRecordedTrackPoint())
    }

    suspend fun localPoints(trackId: String): List<PendingPointEntity> = dao.forTrack(trackId)

    suspend fun updateLocalPoint(
        pointId: String,
        lon: Double,
        lat: Double,
        name: String?,
        desc: String?,
        images: List<String>,
        source: String,
    ): Boolean = mutex.withLock {
        val existing = dao.get(pointId) ?: return@withLock false
        dao.insert(existing.copy(
            lon = lon,
            lat = lat,
            name = name,
            desc = desc,
            imgs = Json.encodeToString(images),
            source = source,
        ))
        true
    }

    suspend fun deleteLocalPoint(pointId: String): Boolean = mutex.withLock {
        dao.delete(pointId) > 0
    }

    suspend fun enqueueAutoPoint(
        trackId: String,
        lon: Double,
        lat: Double,
        timeMillis: Long,
    ): RecordedTrackPoint {
        val normalizedTime = timeMillis.takeIf { it > 0 } ?: System.currentTimeMillis()
        val point = PendingPointEntity(
            trackId = trackId,
            pointId = businessId(),
            lon = lon,
            lat = lat,
            time = normalizedTime,
            source = "AUTO",
            createdAt = System.currentTimeMillis(),
        )
        dao.insert(point)
        return point.toRecordedTrackPoint().also(_recordedPoints::tryEmit)
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

private fun PendingPointEntity.toRecordedTrackPoint() = RecordedTrackPoint(
    trackId = trackId,
    pointId = pointId,
    lon = lon,
    lat = lat,
    timeMillis = time,
)
