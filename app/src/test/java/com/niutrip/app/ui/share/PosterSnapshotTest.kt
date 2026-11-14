package com.niutrip.app.ui.share

import com.niutrip.app.data.remote.PointDto
import com.niutrip.app.data.remote.TrackDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PosterSnapshotTest {
    private fun dto(
        id: String,
        time: String = "2026-09-11T10:00:00",
        lon: Double = 100.0,
        lat: Double = 30.0,
        deleted: Boolean = false,
    ) = PointDto(point_id = id, longitude = lon, latitude = lat, point_time = time,
        point_source = "AUTO", is_deleted = deleted)

    private val track = TrackDto(
        track_id = "t1", track_name = "Trip", track_record_mode = "AUTO", track_status = "FINISHED",
        track_start_time = "2026-09-11T08:00:00", track_end_time = "2026-09-12T18:00:00")

    @Test fun `cloud point wins over local pending with same id`() {
        val snapshot = PosterSnapshotBuilder.build(
            track,
            cloud = listOf(dto("same", lon = 101.0)),
            local = listOf(dto("same", lon = 100.0)),
            dataRevision = 3)
        assertEquals(101.0, snapshot!!.points.single().lon, 1e-9)
        assertEquals(3, snapshot.dataRevision)
    }

    @Test fun `invalid coordinates unparseable times and tombstones are filtered`() {
        val snapshot = PosterSnapshotBuilder.build(
            track,
            cloud = listOf(
                dto("ok1", time = "2026-09-11T09:00:00"),
                dto("badlat", lat = 91.0),
                dto("badlon", lon = 200.0),
                dto("badtime", time = "not-a-time"),
                dto("gone", deleted = true)),
            local = listOf(dto("ok2", time = "2026-09-11T10:00:00")),
            dataRevision = 1)
        assertEquals(listOf("ok1", "ok2"), snapshot!!.points.map { it.id })
    }

    @Test fun `equal timestamps sort by id for stable ordering`() {
        val snapshot = PosterSnapshotBuilder.build(
            track,
            cloud = listOf(dto("b", time = "2026-09-11T09:00:00"), dto("a", time = "2026-09-11T09:00:00")),
            local = emptyList(),
            dataRevision = 1)
        assertEquals(listOf("a", "b"), snapshot!!.points.map { it.id })
    }

    @Test fun `no valid points yields null snapshot`() {
        assertNull(PosterSnapshotBuilder.build(track, cloud = emptyList(), local = listOf(dto("bad", lat = 95.0)), dataRevision = 1))
    }

    @Test fun `points group by day and stats use the merged set`() {
        val snapshot = PosterSnapshotBuilder.build(
            track,
            cloud = listOf(dto("c1", time = "2026-09-11T09:00:00"), dto("c2", time = "2026-09-12T09:00:00")),
            local = listOf(PointDto(point_id = "l1", longitude = 100.0, latitude = 30.0,
                point_time = "2026-09-12T10:00:00", point_source = "MANUAL")),
            dataRevision = 1)
        assertEquals(2, snapshot!!.days.size)
        assertEquals(3, snapshot.points.size)
        assertEquals(2, snapshot.stats.recordDays)
        assertEquals(1, snapshot.stats.checkinCount)
    }
}
