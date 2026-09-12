package com.niutrip.app.ui.tracks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.niutrip.app.data.remote.TrackDto
import com.niutrip.app.data.repo.TrackRepository
import com.niutrip.app.ui.deeplink.DeepLinkHandler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class TrackScope { MINE, SHARED }
data class TrackListState(
    val loading: Boolean = false,
    val mine: List<TrackDto> = emptyList(),
    val shared: List<TrackDto> = emptyList(),
    val selected: TrackScope = TrackScope.MINE,
    val error: String? = null,
    val showImportDialog: Boolean = false,
    val importLink: String = "",
    val importing: Boolean = false,
    val importError: String? = null,
    val notice: String? = null,
    val deletingTrackId: String? = null,
)

class TrackListViewModel(private val repository: TrackRepository) : ViewModel() {
    private val _state = MutableStateFlow(TrackListState())
    val state = _state.asStateFlow()
    fun select(scope: TrackScope) { _state.update { it.copy(selected = scope) }; refresh() }
    fun openImportDialog() = _state.update { it.copy(showImportDialog = true, importError = null) }
    fun dismissImportDialog() {
        if (!_state.value.importing) _state.update { it.copy(showImportDialog = false, importError = null) }
    }
    fun setImportLink(value: String) = _state.update { it.copy(importLink = value, importError = null) }
    fun consumeNotice() = _state.update { it.copy(notice = null) }

    fun delete(track: TrackDto, shared: Boolean, onDeleted: () -> Unit = {}) = viewModelScope.launch {
        _state.update { it.copy(deletingTrackId = track.track_id) }
        runCatching {
            if (shared) repository.deleteShared(track.track_id) else repository.delete(track.track_id)
        }.onSuccess {
            _state.update {
                if (shared) it.copy(
                    shared = it.shared.filterNot { row -> row.track_id == track.track_id },
                    deletingTrackId = null,
                    notice = "已从「分享给我的」移除",
                ) else it.copy(
                    mine = it.mine.filterNot { row -> row.track_id == track.track_id },
                    deletingTrackId = null,
                    notice = "轨迹已删除",
                )
            }
            onDeleted()
        }.onFailure { error ->
            _state.update { it.copy(deletingTrackId = null, notice = error.message ?: "删除失败，请稍后重试") }
        }
    }

    fun importShared() {
        val token = DeepLinkHandler.parseSharedText(_state.value.importLink)
        if (token == null) {
            _state.update { it.copy(importError = "请输入有效的轨迹分享链接") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(importing = true, importError = null) }
            repository.saveShared(token).fold(
                onSuccess = {
                    val rows = repository.listShared().getOrNull()
                    _state.update {
                        it.copy(
                            shared = rows ?: it.shared,
                            showImportDialog = false,
                            importLink = "",
                            importing = false,
                            notice = if (rows == null) "轨迹已添加，可点击刷新查看" else "轨迹已添加到「分享给我的」",
                        )
                    }
                },
                onFailure = { error ->
                    _state.update { it.copy(importing = false, importError = error.message ?: "添加失败，请稍后重试") }
                },
            )
        }
    }
    fun refresh() = viewModelScope.launch {
        _state.update { it.copy(loading = true, error = null) }
        val scope = _state.value.selected
        val result = if (scope == TrackScope.MINE) repository.listMine() else repository.listShared()
        _state.update { old -> result.fold(
            { rows -> if (scope == TrackScope.MINE) old.copy(loading = false, mine = rows) else old.copy(loading = false, shared = rows) },
            { old.copy(loading = false, error = it.message ?: "加载失败") }) }
    }
}
