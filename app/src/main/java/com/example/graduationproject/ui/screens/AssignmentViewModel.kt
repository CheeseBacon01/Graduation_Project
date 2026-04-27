package com.example.graduationproject.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.graduationproject.DataClass.DailyPlan
import com.example.graduationproject.DataClass.Exercise
import com.example.graduationproject.DataClass.ExerciseStatus
import com.example.graduationproject.repository.VivifrailRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

data class AssignmentUiState(
    val dailyPlan: DailyPlan? = null,
    val exercises: List<Exercise> = emptyList(),
    val completedExerciseIds: Set<String> = emptySet()
)

class AssignmentViewModel(
    private val repository: VivifrailRepository = VivifrailRepository()
) : ViewModel() {

    private val _level = MutableStateFlow<String?>(null)
    private val _day = MutableStateFlow(1)
    private val _completedIds = MutableStateFlow<Set<String>>(emptySet())

    // 核心邏輯：只要等級、天數或完成狀態任一改變，立即重新計算 exercises
    val uiState: StateFlow<AssignmentUiState> = combine(_level, _day, _completedIds) { level, day, completedIds ->
        val plan = if (level != null) repository.getDailyPlan(level, day) else null
        val processedExercises = plan?.exercises?.let { list ->
            var foundCurrent = false
            list.map { exercise ->
                when {
                    completedIds.contains(exercise.id) -> exercise.copy(status = ExerciseStatus.COMPLETED)
                    !foundCurrent -> {
                        foundCurrent = true
                        exercise.copy(status = ExerciseStatus.CURRENT)
                    }
                    else -> exercise.copy(status = ExerciseStatus.LOCKED)
                }
            }
        } ?: emptyList()
        
        AssignmentUiState(
            dailyPlan = plan,
            exercises = processedExercises,
            completedExerciseIds = completedIds
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = AssignmentUiState()
    )

    /**
     * 更新目前的訓練等級與天數
     */
    fun updateParams(level: String, day: Int) {
        _level.value = level
        _day.value = day
    }

    fun completeExercise(id: String) {
        _completedIds.update { it + id }
    }
}
