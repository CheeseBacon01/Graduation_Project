package com.example.graduationproject.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SurveyScoringTest {

    @Test
    fun notMeasured_doesNotProduceCompleteResult() {
        val result = calculateSppbResult(validMeasurements().copy(walk4m = TimedMeasurement.NotMeasured), false)

        assertTrue(result is SppbCalculationResult.Incomplete)
    }

    @Test
    fun unableToPerform_scoresZeroAndCanComplete() {
        val result = calculateSppbResult(
            SppbMeasurements(
                sideBySide = TimedMeasurement.UnableToPerform,
                walk4m = TimedMeasurement.UnableToPerform,
                chairStand5x = TimedMeasurement.UnableToPerform
            ),
            false
        )

        assertComplete(result, expectedScore = 0, expectedGrade = "A")
    }

    @Test
    fun invalidMeasurement_doesNotProduceCompleteResult() {
        val result = calculateSppbResult(
            validMeasurements().copy(chairStand5x = TimedMeasurement.InvalidMeasurement),
            false
        )

        assertTrue(result is SppbCalculationResult.Incomplete)
    }

    @Test
    fun walkingWithZeroSeconds_isInvalidForScoring() {
        val result = calculateSppbResult(
            validMeasurements().copy(walk4m = TimedMeasurement.Completed(0f)),
            false
        )

        assertTrue(result is SppbCalculationResult.Incomplete)
    }

    @Test
    fun chairStandWithZeroSeconds_isInvalidForScoring() {
        val result = calculateSppbResult(
            validMeasurements().copy(chairStand5x = TimedMeasurement.Completed(0f)),
            false
        )

        assertTrue(result is SppbCalculationResult.Incomplete)
    }

    @Test
    fun balanceWithImmediateFailureAtZeroSeconds_scoresZero() {
        val result = calculateSppbResult(
            validMeasurements().copy(sideBySide = TimedMeasurement.Completed(0f)),
            false
        )

        assertComplete(result, expectedScore = 8, expectedGrade = "C")
    }

    @Test
    fun incompleteOrInvalidMeasurements_neverProduceGrade() {
        val incomplete = calculateSppbResult(SppbMeasurements(), false)
        val invalid = calculateSppbResult(
            validMeasurements().copy(walk4m = TimedMeasurement.InvalidMeasurement),
            true
        )

        assertTrue(incomplete is SppbCalculationResult.Incomplete)
        assertTrue(invalid is SppbCalculationResult.Incomplete)
    }

    @Test
    fun validMeasurements_keepExistingScoresAndGrade() {
        val result = calculateSppbResult(
            SppbMeasurements(
                sideBySide = TimedMeasurement.Completed(10f),
                semiTandem = TimedMeasurement.Completed(10f),
                tandem = TimedMeasurement.Completed(3f),
                walk4m = TimedMeasurement.Completed(4.82f),
                chairStand5x = TimedMeasurement.Completed(13.69f)
            ),
            false
        )

        assertComplete(result, expectedScore = 9, expectedGrade = "C")
    }

    @Test
    fun validMaximumMeasurements_scoreTwelve() {
        val result = calculateSppbResult(validMeasurements(), false)

        assertComplete(result, expectedScore = 12, expectedGrade = "D")
    }

    @Test
    fun fallRisk_keepsExistingPlusGradeRule() {
        val result = calculateSppbResult(
            validMeasurements().copy(sideBySide = TimedMeasurement.Completed(0f)),
            true
        )

        assertComplete(result, expectedScore = 8, expectedGrade = "C+", expectedFallRisk = true)
    }

    private fun validMeasurements() = SppbMeasurements(
        sideBySide = TimedMeasurement.Completed(10f),
        semiTandem = TimedMeasurement.Completed(10f),
        tandem = TimedMeasurement.Completed(10f),
        walk4m = TimedMeasurement.Completed(4f),
        chairStand5x = TimedMeasurement.Completed(10f)
    )

    private fun assertComplete(
        result: SppbCalculationResult,
        expectedScore: Int,
        expectedGrade: String,
        expectedFallRisk: Boolean = false
    ) {
        assertTrue(result is SppbCalculationResult.Complete)
        result as SppbCalculationResult.Complete
        assertEquals(expectedScore, result.score)
        assertEquals(expectedGrade, result.grade)
        assertEquals(expectedFallRisk, result.hasFallRisk)
    }
}

class SurveyViewModelFlowTest {

    @Test
    fun repeatedUnableForWalking_advancesOnceAndLeavesChairUnmeasured() {
        val viewModel = SurveyViewModel()
        viewModel.markUnableToPerform(SurveyStep.Sppb1A)

        viewModel.markUnableToPerform(SurveyStep.Sppb2)
        viewModel.markUnableToPerform(SurveyStep.Sppb2)

        assertEquals(SurveyStep.Sppb3, viewModel.currentStep)
        assertEquals(4, viewModel.uiState.value.currentStepIndex)
        assertEquals(
            TimedMeasurement.NotMeasured,
            viewModel.measurementFor(SurveyStep.Sppb3)
        )

        viewModel.markMeasurementInvalid(SurveyStep.Sppb3)
        assertEquals("本次測量無效，請重新測量", viewModel.uiState.value.validationMessage)
    }

    @Test
    fun repeatedFallRiskAnswer_answersOnlyExpectedQuestion() {
        val viewModel = viewModelAtFirstFallRisk()

        viewModel.submitFallRiskAnswer(SurveyStep.FallRisk1, false)
        viewModel.submitFallRiskAnswer(SurveyStep.FallRisk1, false)

        assertEquals(SurveyStep.FallRisk2, viewModel.currentStep)
        assertEquals(6, viewModel.uiState.value.currentStepIndex)
        assertFalse(viewModel.hasFallRiskAnswer(SurveyStep.FallRisk2))
        assertTrue(!viewModel.uiState.value.isCompleted)
    }

    @Test
    fun repeatedLastSppbSubmission_doesNotSkipFirstFallRiskQuestion() {
        val viewModel = SurveyViewModel()
        viewModel.markUnableToPerform(SurveyStep.Sppb1A)
        viewModel.markUnableToPerform(SurveyStep.Sppb2)

        viewModel.markUnableToPerform(SurveyStep.Sppb3)
        viewModel.markUnableToPerform(SurveyStep.Sppb3)

        assertEquals(SurveyStep.FallRisk1, viewModel.currentStep)
        assertEquals(5, viewModel.uiState.value.currentStepIndex)
        assertFalse(viewModel.hasFallRiskAnswer(SurveyStep.FallRisk1))
        assertTrue(!viewModel.uiState.value.isCompleted)
    }

    @Test
    fun timerEventsAreIgnoredOnYesNoQuestion() {
        val viewModel = viewModelAtFirstFallRisk()
        val initialState = viewModel.uiState.value

        viewModel.startTimer(SurveyStep.FallRisk1)
        viewModel.pauseTimer(SurveyStep.FallRisk1)
        viewModel.resetTimer(SurveyStep.FallRisk1)
        viewModel.applyTimerToCurrentStep(SurveyStep.FallRisk1)
        viewModel.markUnableToPerform(SurveyStep.FallRisk1)
        viewModel.markMeasurementInvalid(SurveyStep.FallRisk1)

        assertEquals(initialState, viewModel.uiState.value)
        assertEquals(SurveyStep.FallRisk1, viewModel.currentStep)
    }

    @Test
    fun yesNoEventIsIgnoredOnTimerQuestion() {
        val viewModel = SurveyViewModel()
        val initialState = viewModel.uiState.value

        viewModel.submitFallRiskAnswer(SurveyStep.Sppb1A, true)

        assertEquals(initialState, viewModel.uiState.value)
        assertEquals(SurveyStep.Sppb1A, viewModel.currentStep)
    }

    @Test
    fun resetAfterInvalidMeasurement_clearsErrorWithoutChangingQuestion() {
        val viewModel = SurveyViewModel()

        viewModel.markMeasurementInvalid(SurveyStep.Sppb1A)
        assertEquals("本次測量無效，請重新測量", viewModel.uiState.value.validationMessage)

        viewModel.resetTimer(SurveyStep.Sppb1A)
        viewModel.markMeasurementInvalid(SurveyStep.Sppb2)

        assertEquals(SurveyStep.Sppb1A, viewModel.currentStep)
        assertEquals(0, viewModel.uiState.value.currentStepIndex)
        assertEquals(null, viewModel.uiState.value.validationMessage)
        assertEquals(0f, viewModel.uiState.value.timerValue)
        assertTrue(!viewModel.uiState.value.hasTimerStarted)
    }

    @Test
    fun completedFlow_ignoresFurtherSubmissionEvents() {
        val viewModel = viewModelAtFirstFallRisk()
        viewModel.submitFallRiskAnswer(SurveyStep.FallRisk1, false)
        viewModel.submitFallRiskAnswer(SurveyStep.FallRisk2, false)
        viewModel.submitFallRiskAnswer(SurveyStep.FallRisk3, false)
        viewModel.submitFallRiskAnswer(SurveyStep.FallRisk4, false)
        val completedState = viewModel.uiState.value

        viewModel.submitFallRiskAnswer(SurveyStep.FallRisk4, true)
        viewModel.markUnableToPerform(SurveyStep.FallRisk4)

        assertTrue(completedState.isCompleted)
        assertEquals(0, completedState.finalScore)
        assertEquals("A", completedState.finalGrade)
        assertTrue(!completedState.hasFallRisk)
        assertEquals(completedState, viewModel.uiState.value)
    }

    private fun viewModelAtFirstFallRisk(): SurveyViewModel = SurveyViewModel().apply {
        markUnableToPerform(SurveyStep.Sppb1A)
        markUnableToPerform(SurveyStep.Sppb2)
        markUnableToPerform(SurveyStep.Sppb3)
    }
}
