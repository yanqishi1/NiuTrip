package com.niutrip.app.ui.create

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.niutrip.app.data.remote.ApiException
import com.niutrip.app.data.remote.TrackDto
import com.niutrip.app.data.repo.TrackRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface CreateResult { data object Idle : CreateResult; data object Loading : CreateResult; data object NeedPermission : CreateResult; data class Done(val track: TrackDto) : CreateResult; data class Error(val message: String) : CreateResult }
data class CreateTrackState(val name: String = "", val mode: String = "AUTO", val result: CreateResult = CreateResult.Idle)

class CreateTrackViewModel(private val repository: TrackRepository, private val checker: PermissionChecker) : ViewModel() {
    private val _state = MutableStateFlow(CreateTrackState()); val state = _state.asStateFlow()
    fun setName(value: String) = _state.update { it.copy(name = value) }
    fun setMode(value: String) = _state.update { it.copy(mode = value, result = CreateResult.Idle) }
    fun degradeToManual() = setMode("MANUAL")
    fun permissionsChanged() = _state.update { it.copy(result = CreateResult.Idle) }
    fun submit() {
        val current = _state.value
        if (current.name.isBlank()) { _state.update { it.copy(result = CreateResult.Error("请输入轨迹名称")) }; return }
        if (current.mode == "AUTO" && (!checker.hasFineLocation() || !checker.hasBackgroundLocation() || !checker.hasBatteryWhitelist())) {
            _state.update { it.copy(result = CreateResult.NeedPermission) }; return
        }
        viewModelScope.launch {
            _state.update { it.copy(result = CreateResult.Loading) }
            _state.update { old -> try { old.copy(result = CreateResult.Done(repository.create(current.name, current.mode))) }
                catch (error: Throwable) { old.copy(result = CreateResult.Error(if ((error as? ApiException)?.code == 409) "已有正在记录的轨迹" else error.message ?: "创建失败")) } }
        }
    }
}
