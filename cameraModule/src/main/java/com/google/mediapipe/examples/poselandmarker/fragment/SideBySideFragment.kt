package com.google.mediapipe.examples.poselandmarker.fragment

import android.os.Bundle
import android.view.View

class SideBySideFragment : BaseBalanceTestFragment() {
    override val stageName = "並排站立"
    override val instructionMessage = "請並排站立"
    override val passScore = "1分"

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
    }

    override fun isCorrectStance(dx: Float, dy: Float): Boolean {
        return dx < 0.12f && dy < 0.05f
    }

    override fun calculateFailScore(elapsedSeconds: Long): String {
        return "0分"
    }
}