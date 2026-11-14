package com.niutrip.app.ui.share

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.niutrip.app.data.local.FakePendingPointDao
import com.niutrip.app.data.local.PendingPointEntity
import com.niutrip.app.data.local.TrackDao
import com.niutrip.app.data.local.TrackEntity
import com.niutrip.app.data.remote.PointDto
import com.niutrip.app.data.remote.PointsIn
import com.niutrip.app.data.remote.PointsPage
import com.niutrip.app.data.remote.PostPointsOut
import com.niutrip.app.data.remote.ShareIn
import com.niutrip.app.data.remote.ShareOut
import com.niutrip.app.data.remote.StubApi
import com.niutrip.app.data.remote.TrackDto
import com.niutrip.app.data.repo.SyncRepository
import com.niutrip.app.data.repo.TrackRepository
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PosterEditorViewModelTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(testDispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private class PosterApi(private val status: String = "FINISHED") : StubApi() {
        var shareCalls = 0
        override suspend fun track(id: String, recordView: Boolean) = TrackDto(
            track_id = id, track_name = "川西环线", track_record_mode = "AUTO", track_status = status,
            track_start_time = "2026-09-11T08:00:00", track_end_time = "2026-09-12T18:00:00")
        override suspend fun pointsLargePage(id: String, page: Int, pageSize: Int): PointsPage {
            val results = listOf(
                PointDto(point_id = "p1", longitude = 100.0, latitude = 30.0,
                    point_time = "2026-09-11T09:00:00", point_source = "AUTO"),
                PointDto(point_id = "p2", longitude = 102.0, latitude = 30.5,
                    point_time = "2026-09-12T09:00:00", point_source = "AUTO"))
            return PointsPage(2, results = results)
        }
        override suspend fun share(id: String, body: ShareIn): ShareOut {
            shareCalls++
            return ShareOut(body.share_mode, "/t/tok")
        }
        override suspend fun postPoints(id: String, body: PointsIn): PostPointsOut = error("offline")
    }

    private fun vm(api: PosterApi = PosterApi(), local: List<PendingPointEntity> = emptyList()) =
        PosterEditorViewModel(
            "t1", TrackRepository(api, FakeTrackDao), SyncRepository(api, FakePendingPointDao(local)),
            context, ioDispatcher = testDispatcher)

    private fun loadedVm() = vm().apply { load() }

    private fun pickedPhotoFile(): File {
        val bitmap = Bitmap.createBitmap(600, 450, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(90, 120, 160))
        val file = File.createTempFile("pick_", ".jpg", context.cacheDir)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        return file
    }

    private fun decodeWidth(file: File): Int {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        return bounds.outWidth
    }

    @Test fun `load builds snapshot and default map document`() = runTest(testDispatcher) {
        val editor = loadedVm()
        val state = editor.state.value
        assertTrue(state.phase is PosterDataPhase.Ready)
        assertEquals(2, state.snapshot!!.points.size)
        assertEquals(2, state.snapshot!!.stats.recordDays)
        assertEquals(PosterBackgroundMode.MAP, state.document.backgroundMode)
        assertNotNull(state.document.mapInfoState)
        assertEquals(PosterRatioKind.R3_4, state.document.ratioKind)
        assertEquals(0, state.document.editRevision)
    }

    @Test fun `load rejects non finished track`() = runTest(testDispatcher) {
        val editor = vm(PosterApi(status = "RECORDING")).apply { load() }
        assertTrue(editor.state.value.phase is PosterDataPhase.Error)
    }

    @Test fun `picking photo switches mode with default photo layout and preview`() = runTest(testDispatcher) {
        val editor = loadedVm()
        editor.pickPhoto(Uri.fromFile(pickedPhotoFile()))
        val state = editor.state.value
        assertTrue(state.photoPhase is PosterPhotoPhase.Ready)
        assertEquals(PosterBackgroundMode.PHOTO, state.document.backgroundMode)
        assertEquals(600, state.document.photoState!!.imageWidth)
        assertEquals(450, state.document.photoState!!.imageHeight)
        assertNotNull(state.document.photoRouteState)
        assertNotNull(state.document.photoInfoState)
        assertEquals(PosterInfoTextTheme.LIGHT, state.document.photoInfoState!!.textTheme)
        assertNotNull(state.photoPreview)
        assertEquals(1, state.document.editRevision)
        assertTrue(state.hasUnsavedEdits)
    }

    @Test fun `failed photo pick keeps previous document and photo`() = runTest(testDispatcher) {
        val editor = loadedVm()
        editor.pickPhoto(Uri.fromFile(pickedPhotoFile()))
        val before = editor.state.value.document
        val broken = File.createTempFile("broken_", ".jpg", context.cacheDir)
        broken.writeBytes(byteArrayOf(1, 2, 3))
        editor.pickPhoto(Uri.fromFile(broken))
        val state = editor.state.value
        assertTrue(state.photoPhase is PosterPhotoPhase.Error)
        assertEquals(before, state.document)
        broken.delete()
    }

    @Test fun `switching background keeps per-mode states and restores them`() = runTest(testDispatcher) {
        val editor = loadedVm()
        editor.pickPhoto(Uri.fromFile(pickedPhotoFile()))
        val photoInfo = editor.state.value.document.photoInfoState
        editor.setBackgroundMode(PosterBackgroundMode.MAP)
        assertEquals(PosterBackgroundMode.MAP, editor.state.value.document.backgroundMode)
        assertNotNull(editor.state.value.document.mapInfoState)
        editor.setBackgroundMode(PosterBackgroundMode.PHOTO)
        val restored = editor.state.value.document
        assertEquals(photoInfo, restored.photoInfoState)
        assertEquals(PosterBackgroundMode.PHOTO, restored.backgroundMode)
    }

    @Test fun `map mode cannot select original ratio but photo mode can`() = runTest(testDispatcher) {
        val editor = loadedVm()
        editor.setRatio(PosterRatioKind.ORIGINAL)
        assertEquals(PosterRatioKind.R3_4, editor.state.value.document.ratioKind)
        editor.pickPhoto(Uri.fromFile(pickedPhotoFile()))
        editor.setRatio(PosterRatioKind.ORIGINAL)
        val spec = editor.state.value.document.canvasSpec()
        assertEquals(1080, spec.widthPx)
        assertEquals(810, spec.heightPx)
    }

    @Test fun `module drag is live and commit bumps revision once`() = runTest(testDispatcher) {
        val editor = loadedVm()
        editor.pickPhoto(Uri.fromFile(pickedPhotoFile()))
        val start = editor.state.value.document.photoRouteState!!.placement
        editor.moveModule(PosterModule.ROUTE, start.centerX + 0.05f, start.centerY + 0.02f)
        assertEquals(start.centerX + 0.05f, editor.state.value.document.photoRouteState!!.placement.centerX, 1e-5f)
        assertEquals(1, editor.state.value.document.editRevision) // 拖动中不递增
        editor.commitInteraction()
        val committed = editor.state.value.document.photoRouteState!!.placement
        assertEquals(2, editor.state.value.document.editRevision)
        assertTrue(committed.centerX in 0f..1f)
    }

    @Test fun `crop update applies clamped after commit`() = runTest(testDispatcher) {
        val editor = loadedVm()
        editor.pickPhoto(Uri.fromFile(pickedPhotoFile()))
        editor.updateCrop(PhotoCrop(zoom = 40f, offsetX = 9f))
        assertEquals(1, editor.state.value.document.editRevision) // 未提交不递增
        editor.commitInteraction()
        assertEquals(4f, editor.state.value.document.photoState!!.crop.zoom, 1e-4f)
        assertEquals(2, editor.state.value.document.editRevision)
    }

    @Test fun `map camera compose disables autofit and fit restores it`() = runTest(testDispatcher) {
        val editor = loadedVm()
        editor.saveMapCamera(30.2, 101.3, 12.5)
        val saving = editor.state.value.document.mapState
        assertEquals(false, saving.isAutoFit)
        assertEquals(30.2, saving.centerLat!!, 1e-9)
        assertEquals(12.5, saving.zoom!!, 1e-9)
        editor.requestMapFit()
        assertTrue(editor.state.value.document.mapState.isAutoFit)
    }

    @Test fun `export photo poster writes png and edits invalidate it`() = runTest(testDispatcher) {
        val editor = loadedVm()
        editor.pickPhoto(Uri.fromFile(pickedPhotoFile()))
        editor.export()
        val first = editor.state.value.export
        assertTrue(first is PosterExport.Ready)
        val file = (first as PosterExport.Ready).file
        assertTrue(file.exists() && file.length() > 0)
        assertEquals(1080, decodeWidth(file))
        // 导出后再次编辑使结果失效。
        editor.moveModule(PosterModule.ROUTE, 0.8f, 0.8f)
        editor.commitInteraction()
        assertEquals(PosterExport.Idle, editor.state.value.export)
    }

    @Test fun `export map poster uses registered capture`() = runTest(testDispatcher) {
        val editor = loadedVm()
        val mapBitmap = Bitmap.createBitmap(540, 720, Bitmap.Config.ARGB_8888)
        editor.registerMapCapture { mapBitmap }
        editor.export()
        val export = editor.state.value.export
        assertTrue(export is PosterExport.Ready)
        assertEquals(1080, decodeWidth((export as PosterExport.Ready).file))
    }

    @Test fun `map export without capture fails cleanly`() = runTest(testDispatcher) {
        val editor = loadedVm()
        editor.export()
        assertTrue(editor.state.value.export is PosterExport.Error)
    }

    @Test fun `editing is locked while exporting and resumes after ready`() = runTest(testDispatcher) {
        val editor = loadedVm()
        val gate = CompletableDeferred<Unit>()
        val mapBitmap = Bitmap.createBitmap(540, 720, Bitmap.Config.ARGB_8888)
        editor.registerMapCapture { gate.await(); mapBitmap }
        editor.export()
        assertTrue(editor.state.value.export is PosterExport.Rendering)
        editor.setRatio(PosterRatioKind.SQUARE)
        assertEquals(PosterRatioKind.R3_4, editor.state.value.document.ratioKind)
        gate.complete(Unit)
        assertTrue(editor.state.value.export is PosterExport.Ready)
    }

    @Test fun `cancel export ignores late capture result`() = runTest(testDispatcher) {
        val editor = loadedVm()
        val gate = CompletableDeferred<Unit>()
        val mapBitmap = Bitmap.createBitmap(540, 720, Bitmap.Config.ARGB_8888)
        editor.registerMapCapture { gate.await(); mapBitmap }
        editor.export()
        editor.cancelExport()
        gate.complete(Unit)
        assertEquals(PosterExport.Idle, editor.state.value.export)
    }

    // ---------- 阶段 4：撤销重做 / 吸附 / 预设 / 指标开关 ----------

    @Test fun `undo restores previous document but keeps revision increasing`() = runTest(testDispatcher) {
        val editor = loadedVm()
        editor.pickPhoto(Uri.fromFile(pickedPhotoFile()))
        val afterPick = editor.state.value.document
        editor.moveModule(PosterModule.ROUTE, 0.7f, 0.8f)
        editor.commitInteraction()
        val afterDrag = editor.state.value.document
        assertTrue(editor.state.value.canUndo)
        editor.undo()
        val undone = editor.state.value.document
        assertEquals(afterPick.photoRouteState, undone.photoRouteState)
        assertEquals(afterDrag.editRevision + 1, undone.editRevision) // 回退内容但 revision 从当前继续递增（plan §5.4）
        assertTrue(editor.state.value.hasUnsavedEdits)
        assertTrue(editor.state.value.canRedo)
        editor.redo()
        assertEquals(afterDrag.photoRouteState, editor.state.value.document.photoRouteState)
    }

    @Test fun `new edit clears redo stack`() = runTest(testDispatcher) {
        val editor = loadedVm()
        editor.pickPhoto(Uri.fromFile(pickedPhotoFile()))
        editor.moveModule(PosterModule.ROUTE, 0.7f, 0.8f)
        editor.commitInteraction()
        editor.undo()
        editor.moveModule(PosterModule.ROUTE, 0.6f, 0.6f)
        editor.commitInteraction()
        assertTrue(!editor.state.value.canRedo)
        editor.redo() // 无可重做：no-op
        assertEquals(0.6f, editor.state.value.document.photoRouteState!!.placement.centerX, 1e-4f)
    }

    @Test fun `history caps at thirty entries`() = runTest(testDispatcher) {
        val editor = loadedVm()
        editor.pickPhoto(Uri.fromFile(pickedPhotoFile()))
        repeat(35) { index ->
            editor.moveModule(PosterModule.ROUTE, 0.1f + index * 0.01f, 0.7f)
            editor.commitInteraction()
        }
        var undoCount = 0
        while (editor.state.value.canUndo) { editor.undo(); undoCount++ }
        assertEquals(30, undoCount)
    }

    @Test fun `selection and export do not enter history`() = runTest(testDispatcher) {
        val editor = loadedVm()
        editor.selectModule(PosterModule.INFO)
        assertTrue(!editor.state.value.canUndo)
        editor.pickPhoto(Uri.fromFile(pickedPhotoFile()))
        editor.moveModule(PosterModule.ROUTE, 0.6f, 0.8f)
        editor.commitInteraction()
        editor.undo()
        editor.undo() // 回到选图前（地图模式默认文档）
        assertTrue(!editor.state.value.canUndo)
    }

    @Test fun `info flag toggles record history and update aspect`() = runTest(testDispatcher) {
        val editor = loadedVm()
        editor.pickPhoto(Uri.fromFile(pickedPhotoFile()))
        val aspectBefore = editor.state.value.document.photoInfoState!!.placement
        editor.setInfoFlag(PosterInfoFlag.AUX, false)
        val doc = editor.state.value.document
        assertEquals(false, doc.photoInfoState!!.showAux)
        assertTrue(doc.editRevision > 0)
        editor.undo()
        assertEquals(true, editor.state.value.document.photoInfoState!!.showAux)
        editor.setInfoTextTheme(PosterInfoTextTheme.DARK)
        assertEquals(PosterInfoTextTheme.DARK, editor.state.value.document.photoInfoState!!.textTheme)
    }

    @Test fun `route theme and checkin toggle apply to current background`() = runTest(testDispatcher) {
        val editor = loadedVm()
        editor.setRouteTheme(PosterRouteTheme.SINGLE)
        editor.setCheckinMarkers(false)
        val mapDoc = editor.state.value.document
        assertEquals(PosterRouteTheme.SINGLE, mapDoc.mapRouteStyle.theme)
        assertEquals(false, mapDoc.mapRouteStyle.showCheckinMarkers)
        editor.pickPhoto(Uri.fromFile(pickedPhotoFile()))
        editor.setRouteTheme(PosterRouteTheme.BY_DAY)
        editor.setCheckinMarkers(true)
        val photoDoc = editor.state.value.document
        assertEquals(PosterRouteTheme.BY_DAY, photoDoc.photoRouteState!!.theme)
        assertEquals(true, photoDoc.photoRouteState!!.showCheckinMarkers)
        // 照片模式的修改不影响地图模式样式。
        assertEquals(PosterRouteTheme.SINGLE, photoDoc.mapRouteStyle.theme)
    }

    @Test fun `photo preset switches to left right layout and records history`() = runTest(testDispatcher) {
        val editor = loadedVm()
        editor.pickPhoto(Uri.fromFile(pickedPhotoFile()))
        editor.applyPhotoPreset(PosterPresetKind.LEFT_RIGHT)
        val doc = editor.state.value.document
        val routeX = doc.photoRouteState!!.placement.centerX
        val infoX = doc.photoInfoState!!.placement.centerX
        assertTrue(routeX < infoX) // 路线在左、信息在右（plan §6.5）
        editor.undo()
        assertTrue(editor.state.value.document.photoRouteState!!.placement.centerX >
            editor.state.value.document.photoInfoState!!.placement.centerX - 0.4f) // 回到上下布局
    }

    @Test fun `reset layout restores defaults keeping photo and map camera`() = runTest(testDispatcher) {
        val editor = loadedVm()
        editor.saveMapCamera(30.0, 101.0, 13.0)
        editor.pickPhoto(Uri.fromFile(pickedPhotoFile()))
        editor.moveModule(PosterModule.ROUTE, 0.9f, 0.9f)
        editor.commitInteraction()
        editor.resetLayout()
        val doc = editor.state.value.document
        assertNotNull(doc.photoState) // 不更换照片（plan §3.5）
        assertEquals(PosterBackgroundMode.PHOTO, doc.backgroundMode)
        val routePlacement = doc.photoRouteState!!.placement
        assertEquals(0.5f, routePlacement.centerX, 1e-4f) // 上下布局默认居中
        editor.setBackgroundMode(PosterBackgroundMode.MAP)
        assertEquals(30.0, editor.state.value.document.mapState.centerLat!!, 1e-9) // 地图相机不受影响
    }

    @Test fun `moveModule snaps to canvas center and clears guides on commit`() = runTest(testDispatcher) {
        val editor = loadedVm()
        editor.pickPhoto(Uri.fromFile(pickedPhotoFile()))
        editor.moveModule(PosterModule.ROUTE, 0.508f, 0.5f, snapThresholdX = 0.02f, snapThresholdY = 0.02f)
        assertEquals(0.5f, editor.state.value.document.photoRouteState!!.placement.centerX, 1e-4f)
        assertEquals(0.5f, editor.state.value.snapGuides!!.x!!, 1e-4f)
        // 远超脱离阈值且不在其他目标附近：释放。
        editor.moveModule(PosterModule.ROUTE, 0.9f, 0.5f, snapThresholdX = 0.02f, snapThresholdY = 0.02f)
        assertEquals(0.9f, editor.state.value.document.photoRouteState!!.placement.centerX, 1e-4f)
        editor.commitInteraction()
        assertEquals(null, editor.state.value.snapGuides)
    }

    @Test fun `scale module clamps width and records history on commit`() = runTest(testDispatcher) {
        val editor = loadedVm()
        editor.pickPhoto(Uri.fromFile(pickedPhotoFile()))
        editor.selectModule(PosterModule.ROUTE)
        editor.scaleModule(PosterModule.ROUTE, 5f)
        assertEquals(0.96f, editor.state.value.document.photoRouteState!!.placement.width, 1e-4f)
        editor.scaleModule(PosterModule.ROUTE, 0.001f)
        assertEquals(0.12f, editor.state.value.document.photoRouteState!!.placement.width, 1e-4f)
        val revisionBefore = editor.state.value.document.editRevision
        editor.commitInteraction()
        assertEquals(revisionBefore + 1, editor.state.value.document.editRevision)
        assertTrue(editor.state.value.canUndo) // 手柄缩放进入撤销历史
    }

    @Test fun `map mode info position is derived and move is ignored`() = runTest(testDispatcher) {
        val editor = loadedVm()
        val before = editor.state.value.document.mapInfoState!!.placement
        editor.moveModule(PosterModule.INFO, 0.1f, 0.1f)
        assertEquals(before, editor.state.value.document.mapInfoState!!.placement)
    }

    private object FakeTrackDao : TrackDao {
        override suspend fun upsert(track: TrackEntity) = Unit
        override suspend fun upsertAll(tracks: List<TrackEntity>) = Unit
        override fun observeAll() = flowOf(emptyList<TrackEntity>())
        override suspend fun get(id: String): TrackEntity? = null
        override suspend fun recording(): TrackEntity? = null
        override suspend fun clear() = Unit
    }
}
