package com.niutrip.app.ui.share

import android.content.Context
import com.amap.api.services.core.LatLonPoint
import com.amap.api.services.geocoder.GeocodeResult
import com.amap.api.services.geocoder.GeocodeSearch
import com.amap.api.services.geocoder.RegeocodeQuery
import com.amap.api.services.geocoder.RegeocodeResult
import com.niutrip.app.core.PointLite
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** Only query the two endpoints, using the same GCJ-02 coordinate system as the map. */
class PosterPlaceResolver(private val context: Context) {
    private val cache = mutableMapOf<Pair<Double, Double>, String>()

    suspend fun resolve(point: PointLite): String? {
        if (!point.name.isNullOrBlank()) return point.name.trim()
        val key = point.lat to point.lon
        cache[key]?.let { return it }
        return withTimeoutOrNull(6_000) {
            suspendCancellableCoroutine<String?> { continuation ->
                val search = GeocodeSearch(context.applicationContext)
                search.setOnGeocodeSearchListener(object : GeocodeSearch.OnGeocodeSearchListener {
                    override fun onGeocodeSearched(result: GeocodeResult?, code: Int) = Unit
                    override fun onRegeocodeSearched(result: RegeocodeResult?, code: Int) {
                        val district = if (code == 1000) result?.regeocodeAddress?.district
                            ?.trim()?.takeIf { it.isNotEmpty() } else null
                        if (district != null) cache[key] = district
                        if (continuation.isActive) continuation.resumeWith(Result.success(district))
                        search.setOnGeocodeSearchListener(null)
                    }
                })
                continuation.invokeOnCancellation { search.setOnGeocodeSearchListener(null) }
                search.getFromLocationAsyn(RegeocodeQuery(LatLonPoint(point.lat, point.lon), 200f, GeocodeSearch.AMAP))
            }
        }
    }
}
