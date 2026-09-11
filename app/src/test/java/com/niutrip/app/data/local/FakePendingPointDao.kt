package com.niutrip.app.data.local

/** 测试基座：内存版 pending_points DAO，语义与 Room 实现一致。 */
open class FakePendingPointDao(initial: List<PendingPointEntity> = emptyList()) : PendingPointDao {
    val rows = initial.toMutableList()
    var totalInserted = 0
    override suspend fun insert(entity: PendingPointEntity): Long { rows.add(entity); totalInserted++; return entity.id }
    override suspend fun takeFirst(count: Int): List<PendingPointEntity> = rows.sortedBy { it.time }.take(count)
    override suspend fun deleteAll(ids: List<Long>) { rows.removeAll { it.id in ids } }
    override suspend fun count(trackId: String?): Int = rows.size
    override suspend fun lastTime(trackId: String): Long? = rows.maxOfOrNull { it.time }
}
