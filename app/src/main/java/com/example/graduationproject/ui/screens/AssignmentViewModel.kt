package com.example.graduationproject.ui.screens

import androidx.lifecycle.ViewModel
import com.example.graduationproject.DataClass.DailyPlan
import com.example.graduationproject.DataClass.Exercise
import com.example.graduationproject.DataClass.ExerciseStatus
import com.example.graduationproject.repository.VivifrailRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AssignmentUiState(
    val dailyPlan: DailyPlan? = null,
    val exercises: List<Exercise> = emptyList(),
    val completedExerciseIds: Set<String> = emptySet()
)

internal data class TrainingAttempt(
    val id: Long,
    val exerciseId: String
)

class AssignmentViewModel(
    private val repository: VivifrailRepository = VivifrailRepository()
) : ViewModel() {

    private var level: String? = null
    private var day: Int = 1
    private var completedIds: Set<String> = emptySet()
    private var nextTrainingAttemptId = 1L
    private var activeTrainingAttempt: TrainingAttempt? = null

    private val _uiState = MutableStateFlow(AssignmentUiState())
    val uiState: StateFlow<AssignmentUiState> = _uiState.asStateFlow()

    private fun refreshUiState() {
        val plan = level?.let { repository.getDailyPlan(it, day) }
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
        
        _uiState.value = AssignmentUiState(
            dailyPlan = plan,
            exercises = processedExercises,
            completedExerciseIds = completedIds
        )
    }

    /**
     * 更新目前的訓練等級與天數
     */
    fun updateParams(level: String, day: Int) {
        this.level = level
        this.day = day
        refreshUiState()
    }

    internal fun beginTraining(exerciseId: String): TrainingAttempt? {
        if (activeTrainingAttempt != null || currentExerciseId() != exerciseId) return null

        return TrainingAttempt(nextTrainingAttemptId++, exerciseId).also {
            activeTrainingAttempt = it
        }
    }

    internal fun applyTrainingResult(
        attemptId: Long,
        exerciseId: String,
        completed: Boolean
    ): Boolean {
        val activeAttempt = activeTrainingAttempt
        if (activeAttempt?.id != attemptId || activeAttempt.exerciseId != exerciseId) return false

        activeTrainingAttempt = null
        if (!completed || currentExerciseId() != exerciseId) return false

        completedIds = completedIds + exerciseId
        refreshUiState()
        return true
    }

    internal fun cancelActiveTraining() {
        activeTrainingAttempt = null
    }

    private fun currentExerciseId(): String? {
        val currentLevel = level ?: return null
        return repository.getDailyPlan(currentLevel, day)
            .exercises
            .firstOrNull { it.id !in completedIds }
            ?.id
    }
}
