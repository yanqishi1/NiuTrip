package com.niutrip.app.ui.share

import android.content.Context
import com.niutrip.app.core.PointLite
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.tan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

data class PosterOutlineResult(val outline: PosterMapOutline, val level: String = "省界", val warning: String? = null)

/** Requests contain only public administrative codes; route coordinates never leave the device. */
class PosterBoundaryRepository(
    private val context: Context,
    private val download: suspend (Int) -> String = ::downloadBoundary,
) {
    suspend fun forPoints(points: List<PointLite>): PosterOutlineResult = withContext(Dispatchers.IO) {
        select(PosterMapOutline.load(context), points) { code -> readBoundary(code) }
    }

    private suspend fun readBoundary(code: Int): PosterMapOutline {
        val directory = File(context.cacheDir, "poster_boundaries").apply { mkdirs() }
        val file = File(directory, "$code.json")
        if (file.isFile && System.currentTimeMillis() - file.lastModified() < CACHE_AGE_MS) {
            runCatching { PosterMapOutline.fromJson(file.readText()) }.getOrNull()?.let { return it }
        }
        val json = download(code)
        val outline = PosterMapOutline.fromJson(json)
        val temp = File.createTempFile("boundary_", ".tmp", directory)
        try {
            temp.writeText(json)
            check(temp.renameTo(file)) { "地图边界缓存写入失败" }
        } finally { temp.delete() }
        var total = directory.listFiles().orEmpty().sumOf { it.length() }
        directory.listFiles().orEmpty().filter { it != file }.sortedBy { it.lastModified() }.forEach {
            if (total > CACHE_BYTES && it.delete()) total -= it.length()
        }
        return outline
    }

    companion object {
        private const val CACHE_AGE_MS = 30L * 24 * 60 * 60 * 1000
        private const val CACHE_BYTES = 32L * 1024 * 1024
        private val client = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS).build()

        internal suspend fun select(provinces: PosterMapOutline, points: List<PointLite>,
                                    children: suspend (Int) -> PosterMapOutline): PosterOutlineResult {
            val projected = points.map { PosterPointF(it.lon,
                ln(tan(PI / 4 + it.lat.coerceIn(-85.05112878, 85.05112878) * PI / 360)) * 180 / PI) }
            val province = provinces.containing(projected) ?: return PosterOutlineResult(provinces)
            var current = PosterOutlineResult(provinces)
            try {
                val cities = children(province.code)
                val districtsOnly = cities.areas.all { it.level == "district" }
                current = PosterOutlineResult(cities, if (districtsOnly) "区县界" else "市界")
                val city = cities.containing(projected) ?: return current
                if (city.level == "district") return current.copy(outline = cities.focusedOn(city))
                if (city.level != "city") return current
                val districts = children(city.code)
                val district = districts.containing(projected)
                return PosterOutlineResult(district?.let(districts::focusedOn) ?: districts, "区县界")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { return current.copy(warning = "细级轮廓暂不可用，已使用${current.level}") }
        }

        private suspend fun downloadBoundary(code: Int): String = suspendCancellableCoroutine { continuation ->
            val call = client.newCall(Request.Builder()
                .url("https://geo.datav.aliyun.com/areas_v3/bound/${code}_full.json").build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching { response.use {
                        check(it.isSuccessful) { "地图边界服务暂不可用" }
                        val source = requireNotNull(it.body).source()
                        source.request(8L * 1024 * 1024 + 1)
                        check(source.buffer.size <= 8L * 1024 * 1024) { "地图边界数据过大" }
                        source.readUtf8()
                    } }
                    if (continuation.isActive) result.fold(continuation::resume, continuation::resumeWithException)
                }
            })
        }
    }
}
