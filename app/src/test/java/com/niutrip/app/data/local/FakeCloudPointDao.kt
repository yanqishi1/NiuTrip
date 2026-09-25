package com.niutrip.app.data.local

open class FakeCloudPointDao(
    initialPoints: List<CloudPointEntity> = emptyList(),
    initialStates: List<PointSyncStateEntity> = emptyList(),
) : CloudPointDao {
    val rows = initialPoints.toMutableList()
    val states = initialStates.associateByTo(mutableMapOf(), PointSyncStateEntity::trackId)

    override suspend fun forTrack(trackId: String): List<CloudPointEntity> =
        rows.filter { it.trackId == trackId }.sortedWith(
            compareBy<CloudPointEntity>(CloudPointEntity::pointTime)
                .thenBy(CloudPointEntity::pointId))

    override suspend fun syncState(trackId: String): PointSyncStateEntity? = states[trackId]

    override suspend fun upsertAll(points: List<CloudPointEntity>) {
        val ids = points.mapTo(mutableSetOf(), CloudPointEntity::pointId)
        rows.removeAll { it.pointId in ids }
        rows.addAll(points)
    }

    override suspend fun deletePoints(pointIds: List<String>) {
        rows.removeAll { it.pointId in pointIds }
    }

    override suspend fun saveSyncState(state: PointSyncStateEntity) {
        states[state.trackId] = state
    }

    override suspend fun deleteTrackPoints(trackId: String) {
        rows.removeAll { it.trackId == trackId }
    }

    override suspend fun deleteSyncState(trackId: String) {
        states.remove(trackId)
    }
}
