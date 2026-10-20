package com.selfuse.cellmap.ui.label

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.selfuse.cellmap.CellMapApp
import com.selfuse.cellmap.core.model.LabelStats
import com.selfuse.cellmap.core.model.ReadinessState
import com.selfuse.cellmap.core.model.TaskItem
import com.selfuse.cellmap.core.model.TaskType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class LabelUiState(
    val readiness: ReadinessState = ReadinessState(),
    val stats: LabelStats = LabelStats(),
    val task: TaskItem? = null,
    val message: String? = null
) {
    val busy: Boolean get() = task?.active == true
    val canConfirm: Boolean
        get() = stats.unclassified > 0 && !readiness.labelReady && stats.completedAt > 0L
}

private data class LabelExtra(val message: String? = null)

class LabelViewModel(app: CellMapApp) : ViewModel() {
    private val c = app.container
    private val extra = MutableStateFlow(LabelExtra())

    val state: StateFlow<LabelUiState> = combine(
        c.readiness.state,
        c.prefs.labelStatsFlow,
        c.taskCenter.observeTasks(),
        extra
    ) { readiness, stats, tasks, ex ->
        LabelUiState(
            readiness = readiness,
            stats = stats,
            task = tasks.firstOrNull { it.type == TaskType.LABEL },
            message = ex.message
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LabelUiState())

    fun run() {
        if (state.value.busy) return
        if (!state.value.readiness.dataReady) {
            extra.update { LabelExtra("尚未导入基站数据，请先完成 XLSX 导入") }
            return
        }
        c.scheduler.enqueueLabel()
        extra.update { LabelExtra("已开始分类标注，完成后会自动进入压缩优化") }
    }

    fun retry() {
        c.scheduler.enqueueLabel()
        extra.update { LabelExtra("已重新提交分类标注任务") }
    }

    fun cancel() {
        viewModelScope.launch { c.taskCenter.cancel(TaskType.LABEL) }
    }

    fun confirm() {
        viewModelScope.launch {
            c.readiness.confirmLabels()
            extra.update { LabelExtra("已确认未归类警告，地图标注已启用") }
        }
    }

    fun consumeMessage() {
        extra.update { LabelExtra(null) }
    }

    companion object {
        fun factory(app: CellMapApp) = viewModelFactory {
            initializer { LabelViewModel(app) }
        }
    }
}
