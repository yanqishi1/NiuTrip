package com.niutrip.app.data.local

/** 测试基座：内存版 pending_points DAO，语义与 Room 实现一致。 */
open class FakePendingPointDao(initial: List<PendingPointEntity> = emptyList()) : PendingPointDao {
    val rows = initial.toMutableList()
    var totalInserted = 0
    override suspend fun insert(entity: PendingPointEntity): Long {
        rows.removeAll { it.pointId == entity.pointId }
        rows.add(entity)
        totalInserted++
        return entity.id
    }
    override suspend fun takeFirst(count: Int): List<PendingPointEntity> = rows.sortedBy { it.time }.take(count)
    override suspend fun forTrack(trackId: String): List<PendingPointEntity> = rows.filter { it.trackId == trackId }.sortedBy { it.time }
    override suspend fun get(pointId: String): PendingPointEntity? = rows.firstOrNull { it.pointId == pointId }
    override suspend fun deleteAll(ids: List<Long>) { rows.removeAll { it.id in ids } }
    override suspend fun delete(pointId: String): Int {
        val removed = rows.count { it.pointId == pointId }
        rows.removeAll { it.pointId == pointId }
        return removed
    }
    override suspend fun deletePoints(pointIds: List<String>): Int {
        val removed = rows.count { it.pointId in pointIds }
        rows.removeAll { it.pointId in pointIds }
        return removed
    }
    override suspend fun count(trackId: String?): Int = rows.size
}
