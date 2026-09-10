package com.google.mediapipe.examples.poselandmarker.fragment

import android.os.Bundle
import android.view.View

class TandemFragment : BaseBalanceTestFragment() {
    override val stageName = "直線站立"
    override val instructionMessage = "請直線站立"
    override val passScore = "2分"

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
    }

    override fun isCorrectStance(dx: Float, dy: Float): Boolean {
        return dx < 0.05f && dy >= 0.05f
    }

    override fun calculateFailScore(elapsedSeconds: Long): String {
        return if (elapsedSeconds < 3) "0分" else "1分"
    }
}