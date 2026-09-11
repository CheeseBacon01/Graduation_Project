package com.example.graduationproject.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed class SurveyStep(
    val title: String,
    val description: String,
    val type: StepType
) {
    enum class StepType { TIMER, YES_NO }

    object Sppb1A : SurveyStep("SPPB 1A: 雙腳並排站立", "請雙腳併攏站立，盡可能維持平衡直到 10 秒。", StepType.TIMER)
    object Sppb1B : SurveyStep("SPPB 1B: 雙腳半並排站立", "請將一腳足弓貼住另一腳大姆指旁站立直到 10 秒。", StepType.TIMER)
    object Sppb1C : SurveyStep("SPPB 1C: 雙腳直線站立", "請將一腳跟貼住另一腳尖，呈直線站立直到 10 秒。", StepType.TIMER)
    object Sppb2 : SurveyStep("SPPB 2: 4 公尺步行速度", "請依正常步速行走 4 公尺。", StepType.TIMER)
    object Sppb3 : SurveyStep("SPPB 3: 5 次從椅子起身", "雙手交叉胸前，盡快完成 5 次坐下與起身。", StepType.TIMER)
    object FallRisk1 : SurveyStep("FallRisk 1: 跌倒史", "過去一年是否曾跌倒 >= 2 次，或曾因跌倒就醫？", StepType.YES_NO)
    object FallRisk2 : SurveyStep("FallRisk 2: TUG 測試", "3 公尺起身行走 (TUG) 是否超過 20 秒？", StepType.YES_NO)
    object FallRisk3 : SurveyStep("FallRisk 3: 步行速度", "6 公尺步行速度是否超過 7.5 秒？", StepType.YES_NO)
    object FallRisk4 : SurveyStep("FallRisk 4: 認知狀態", "長輩目前是否具備中度認知退化跡象？", StepType.YES_NO)
}

internal sealed interface TimedMeasurement {
    data object NotMeasured : TimedMeasurement
    data object UnableToPerform : TimedMeasurement
    data object InvalidMeasurement : TimedMeasurement
    data class Completed(val seconds: Float) : TimedMeasurement
}

internal data class SppbMeasurements(
    val sideBySide: TimedMeasurement = TimedMeasurement.NotMeasured,
    val semiTandem: TimedMeasurement = TimedMeasurement.NotMeasured,
    val tandem: TimedMeasurement = TimedMeasurement.NotMeasured,
    val walk4m: TimedMeasurement = TimedMeasurement.NotMeasured,
    val chairStand5x: TimedMeasurement = TimedMeasurement.NotMeasured
)

internal sealed interface SppbCalculationResult {
    data class Complete(
        val score: Int,
        val grade: String,
        val hasFallRisk: Boolean
    ) : SppbCalculationResult

    data object Incomplete : SppbCalculationResult
}

data class SurveyUiState(
    val currentStepIndex: Int = 0,
    val isCompleted: Boolean = false,
    val timerValue: Float = 0f,
    val isTimerRunning: Boolean = false,
    val hasTimerStarted: Boolean = false,
    val validationMessage: String? = null,
    val finalGrade: String? = null,
    val finalScore: Int? = null,
    val hasFallRisk: Boolean = false
)

class SurveyViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(SurveyUiState())
    val uiState = _uiState.asStateFlow()

    val steps = listOf(
        SurveyStep.Sppb1A, SurveyStep.Sppb1B, SurveyStep.Sppb1C,
        SurveyStep.Sppb2, SurveyStep.Sppb3,
        SurveyStep.FallRisk1, SurveyStep.FallRisk2, SurveyStep.FallRisk3, SurveyStep.FallRisk4
    )

    private var measurements = SppbMeasurements()
    private val fallRiskAnswers = mutableMapOf<Int, Boolean>()
    private var timerJob: Job? = null

    val currentStep: SurveyStep
        get() = steps[_uiState.value.currentStepIndex]

    val progress: Float
        get() = (_uiState.value.currentStepIndex + 1).toFloat() / steps.size

    internal fun measurementFor(step: SurveyStep): TimedMeasurement = when (step) {
        SurveyStep.Sppb1A -> measurements.sideBySide
        SurveyStep.Sppb1B -> measurements.semiTandem
        SurveyStep.Sppb1C -> measurements.tandem
        SurveyStep.Sppb2 -> measurements.walk4m
        SurveyStep.Sppb3 -> measurements.chairStand5x
        else -> error("Step is not an SPPB measurement")
    }

    internal fun hasFallRiskAnswer(step: SurveyStep): Boolean {
        if (step.type != SurveyStep.StepType.YES_NO) return false
        val stepIndex = steps.indexOf(step)
        return stepIndex >= 0 && fallRiskAnswers.containsKey(stepIndex)
    }

    fun startTimer(expectedStep: SurveyStep) {
        if (!isCurrentStep(expectedStep, SurveyStep.StepType.TIMER) || _uiState.value.isTimerRunning) return
        _uiState.update {
            it.copy(
                isTimerRunning = true,
                hasTimerStarted = true,
                validationMessage = null
            )
        }
        timerJob = viewModelScope.launch {
            while (true) {
                delay(100)
                _uiState.update { it.copy(timerValue = it.timerValue + 0.1f) }
            }
        }
    }

    fun pauseTimer(expectedStep: SurveyStep) {
        if (!isCurrentStep(expectedStep, SurveyStep.StepType.TIMER)) return
        stopTimer()
    }

    private fun stopTimer() {
        timerJob?.cancel()
        timerJob = null
        _uiState.update { it.copy(isTimerRunning = false) }
    }

    fun resetTimer(expectedStep: SurveyStep) {
        if (!isCurrentStep(expectedStep, SurveyStep.StepType.TIMER)) return
        resetTimerState()
    }

    private fun resetTimerState() {
        stopTimer()
        _uiState.update {
            it.copy(
                timerValue = 0f,
                hasTimerStarted = false,
                validationMessage = null
            )
        }
    }

    fun applyTimerToCurrentStep(expectedStep: SurveyStep) {
        if (!isCurrentStep(expectedStep, SurveyStep.StepType.TIMER)) return
        if (!_uiState.value.hasTimerStarted) {
            _uiState.update { it.copy(validationMessage = "請先開始測量") }
            return
        }

        stopTimer()
        val seconds = _uiState.value.timerValue
        val measurement = if (isValidCompletedTime(expectedStep, seconds)) {
            TimedMeasurement.Completed(seconds)
        } else {
            TimedMeasurement.InvalidMeasurement
        }

        if (measurement == TimedMeasurement.InvalidMeasurement) {
            measurements = measurements.withMeasurement(expectedStep, measurement)
            _uiState.update {
                it.copy(
                    validationMessage = "測量結果無效，請重新測量"
                )
            }
            return
        }

        submitMeasurement(expectedStep, measurement)
    }

    fun markUnableToPerform(expectedStep: SurveyStep) {
        if (!isCurrentStep(expectedStep, SurveyStep.StepType.TIMER)) return
        stopTimer()
        submitMeasurement(expectedStep, TimedMeasurement.UnableToPerform)
    }

    fun markMeasurementInvalid(expectedStep: SurveyStep) {
        if (!isCurrentStep(expectedStep, SurveyStep.StepType.TIMER)) return
        stopTimer()
        measurements = measurements.withMeasurement(expectedStep, TimedMeasurement.InvalidMeasurement)
        _uiState.update {
            it.copy(
                timerValue = 0f,
                isTimerRunning = false,
                hasTimerStarted = false,
                validationMessage = "本次測量無效，請重新測量"
            )
        }
    }

    fun submitFallRiskAnswer(expectedStep: SurveyStep, value: Boolean) {
        if (!isCurrentStep(expectedStep, SurveyStep.StepType.YES_NO)) return
        val currentIndex = _uiState.value.currentStepIndex
        fallRiskAnswers[currentIndex] = value
        advanceTo(currentIndex + 1)
    }

    private fun submitMeasurement(expectedStep: SurveyStep, measurement: TimedMeasurement) {
        val currentIndex = _uiState.value.currentStepIndex
        measurements = measurements.withMeasurement(expectedStep, measurement)

        val nextIndex = when {
            expectedStep == SurveyStep.Sppb1A && !measurement.passesTenSeconds() -> 3
            expectedStep == SurveyStep.Sppb1B && !measurement.passesTenSeconds() -> 3
            else -> currentIndex + 1
        }
        advanceTo(nextIndex)
    }

    private fun advanceTo(nextIndex: Int) {
        resetTimerState()
        if (nextIndex >= steps.size) {
            calculateResult()
        } else {
            _uiState.update { it.copy(currentStepIndex = nextIndex) }
        }
    }

    private fun isCurrentStep(expectedStep: SurveyStep, expectedType: SurveyStep.StepType): Boolean =
        !_uiState.value.isCompleted && expectedStep.type == expectedType && currentStep == expectedStep

    private fun calculateResult() {
        if (!(5..8).all(fallRiskAnswers::containsKey)) {
            _uiState.update { it.copy(validationMessage = "請完成所有跌倒風險問題") }
            return
        }

        val hasFallRisk = (5..8).any { fallRiskAnswers[it] == true }
        when (val result = calculateSppbResult(measurements, hasFallRisk)) {
            SppbCalculationResult.Incomplete -> {
                _uiState.update { it.copy(validationMessage = "尚有未完成或無效的測量") }
            }
            is SppbCalculationResult.Complete -> {
                _uiState.update {
                    it.copy(
                        isCompleted = true,
                        finalScore = result.score,
                        finalGrade = result.grade,
                        hasFallRisk = result.hasFallRisk,
                        validationMessage = null
                    )
                }
            }
        }
    }
}

private fun SppbMeasurements.withMeasurement(
    step: SurveyStep,
    measurement: TimedMeasurement
): SppbMeasurements = when (step) {
    SurveyStep.Sppb1A -> copy(sideBySide = measurement)
    SurveyStep.Sppb1B -> copy(semiTandem = measurement)
    SurveyStep.Sppb1C -> copy(tandem = measurement)
    SurveyStep.Sppb2 -> copy(walk4m = measurement)
    SurveyStep.Sppb3 -> copy(chairStand5x = measurement)
    else -> this
}

private fun TimedMeasurement.passesTenSeconds(): Boolean =
    this is TimedMeasurement.Completed && seconds.isFinite() && seconds >= 10f

private fun isValidCompletedTime(step: SurveyStep, seconds: Float): Boolean {
    if (!seconds.isFinite()) return false
    return when (step) {
        SurveyStep.Sppb1A, SurveyStep.Sppb1B, SurveyStep.Sppb1C -> seconds >= 0f
        SurveyStep.Sppb2, SurveyStep.Sppb3 -> seconds > 0f
        else -> false
    }
}

internal fun calculateSppbResult(
    measurements: SppbMeasurements,
    hasFallRisk: Boolean
): SppbCalculationResult {
    val balanceScore = calculateBalanceScore(measurements) ?: return SppbCalculationResult.Incomplete
    val walkScore = calculateWalkScore(measurements.walk4m) ?: return SppbCalculationResult.Incomplete
    val chairScore = calculateChairStandScore(measurements.chairStand5x) ?: return SppbCalculationResult.Incomplete
    val totalScore = balanceScore + walkScore + chairScore
    val grade = when (totalScore) {
        in 0..3 -> "A"
        in 4..6 -> if (hasFallRisk) "B+" else "B"
        in 7..9 -> if (hasFallRisk) "C+" else "C"
        else -> "D"
    }
    return SppbCalculationResult.Complete(totalScore, grade, hasFallRisk)
}

private fun calculateBalanceScore(measurements: SppbMeasurements): Int? {
    val sideBySideSeconds = measurements.sideBySide.completedBalanceSecondsOrNull()
        ?: return if (measurements.sideBySide == TimedMeasurement.UnableToPerform) 0 else null
    if (sideBySideSeconds < 10f) return 0

    val semiTandemSeconds = measurements.semiTandem.completedBalanceSecondsOrNull()
        ?: return if (measurements.semiTandem == TimedMeasurement.UnableToPerform) 1 else null
    if (semiTandemSeconds < 10f) return 1

    val tandemSeconds = measurements.tandem.completedBalanceSecondsOrNull()
        ?: return if (measurements.tandem == TimedMeasurement.UnableToPerform) 2 else null
    return 2 + when {
        tandemSeconds >= 10f -> 2
        tandemSeconds >= 3f -> 1
        else -> 0
    }
}

private fun TimedMeasurement.completedBalanceSecondsOrNull(): Float? =
    (this as? TimedMeasurement.Completed)?.seconds?.takeIf { it.isFinite() && it >= 0f }

private fun calculateWalkScore(measurement: TimedMeasurement): Int? = when (measurement) {
    TimedMeasurement.UnableToPerform -> 0
    TimedMeasurement.NotMeasured, TimedMeasurement.InvalidMeasurement -> null
    is TimedMeasurement.Completed -> measurement.seconds.takeIf { it.isFinite() && it > 0f }?.let {
        when {
            it < 4.82f -> 4
            it <= 6.20f -> 3
            it <= 8.70f -> 2
            else -> 1
        }
    }
}

private fun calculateChairStandScore(measurement: TimedMeasurement): Int? = when (measurement) {
    TimedMeasurement.UnableToPerform -> 0
    TimedMeasurement.NotMeasured, TimedMeasurement.InvalidMeasurement -> null
    is TimedMeasurement.Completed -> measurement.seconds.takeIf { it.isFinite() && it > 0f }?.let {
        when {
            it < 11.19f -> 4
            it <= 13.69f -> 3
            it <= 16.69f -> 2
            it <= 59f -> 1
            else -> 0
        }
    }
}
