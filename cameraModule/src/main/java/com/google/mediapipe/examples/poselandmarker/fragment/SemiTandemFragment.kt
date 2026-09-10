package com.google.mediapipe.examples.poselandmarker.fragment

import android.os.Bundle
import android.view.View

class SemiTandemFragment : BaseBalanceTestFragment() {
    override val stageName = "半並排站立"
    override val instructionMessage = "請半並排站立"
    override val passScore = "1分"

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
    }

    override fun isCorrectStance(dx: Float, dy: Float): Boolean {
        return dy >= 0.04f
    }

    override fun calculateFailScore(elapsedSeconds: Long): String {
        return "0分"
    }
}