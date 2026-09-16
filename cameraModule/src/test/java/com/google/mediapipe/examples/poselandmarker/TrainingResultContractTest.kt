package com.google.mediapipe.examples.poselandmarker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrainingResultContractTest {

    @Test
    fun expectedCodeMatchesOnlyTheCompletedFragmentResult() {
        assertTrue(resultContainsExpectedCode("A-3,B-2,C-3,D-3", "B-2"))
        assertFalse(resultContainsExpectedCode("A-3,B-2,C-3,D-3", "B-3"))
        assertFalse(resultContainsExpectedCode("A-3,B-2,C-3,D-3", null))
    }
}
