/*
 * Copyright 2023 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.mediapipe.examples.poselandmarker.fragment

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.CountDownTimer
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.camera.core.Preview
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Camera
import androidx.camera.core.AspectRatio
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.google.mediapipe.examples.poselandmarker.PoseLandmarkerHelper
import com.google.mediapipe.examples.poselandmarker.MainViewModel
import com.google.mediapipe.examples.poselandmarker.R
import com.google.mediapipe.examples.poselandmarker.databinding.FragmentToeHeelWalkingBinding
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

import android.app.AlertDialog
import android.graphics.Color
import android.text.InputType
import android.util.TypedValue
import android.widget.EditText
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.google.mediapipe.examples.poselandmarker.ExerciseResult

class ToeHeelWalkingFragment : Fragment(), PoseLandmarkerHelper.LandmarkerListener {

    enum class Mode { TOE, HEEL }
    enum class State { WAITING_FOR_DISTANCE, COUNTDOWN, WALKING, RESTING_SHORT, RESTING_SET }

    companion object {
        private const val TAG = "ToeHeelWalkingFragment"
        private const val STEPS_PER_PHASE = 7
        private const val TOTAL_SETS = 3
        private const val SHORT_REST_MS = 5000L
        private const val LONG_REST_MS = 60000L
        private const val VISIBILITY_THRESHOLD = 0.3f
        private const val MIN_STEP_DISTANCE = 0.0008f
        private const val TOE_HEEL_Y_DIFF_THRESHOLD = 0.0008f // 判定踮腳或腳跟走的位移閾值
    }

    private var _binding: FragmentToeHeelWalkingBinding? = null
    private val binding get() = _binding!!

    private lateinit var poseLandmarkerHelper: PoseLandmarkerHelper
    private val viewModel: MainViewModel by activityViewModels()
    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraFacing = CameraSelector.LENS_FACING_FRONT

    // Test Variables
    private var currentSet = 1
    private var currentPhaseMode = Mode.TOE
    private var currentState = State.WALKING
    private var stepsInCurrentPhase = 0
    private var lastLeadingFoot = -1 // -1: None, 0: Left, 1: Right
    
    private var totalBalanceScore = 0f
    private var balanceTicks = 0
    private var isTestCompleted = false
    private var isTrainingStarted = false
    private var timer: CountDownTimer? = null
    private var currentTimerStatusText = ""

    private lateinit var backgroundExecutor: ExecutorService

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentToeHeelWalkingBinding.inflate(inflater, container, false)
        return binding.root
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        backgroundExecutor = Executors.newSingleThreadExecutor()

        binding.btnStartTraining.setOnClickListener {
            isTrainingStarted = true
            binding.setupPanel.visibility = View.GONE
            showHeightDialog()
            binding.viewFinder.post { setUpCamera() }
            backgroundExecutor.execute {
                poseLandmarkerHelper = PoseLandmarkerHelper(
                    context = requireContext(),
                    runningMode = RunningMode.LIVE_STREAM,
                    minPoseDetectionConfidence = viewModel.currentMinPoseDetectionConfidence,
                    minPoseTrackingConfidence = viewModel.currentMinPoseTrackingConfidence,
                    minPosePresenceConfidence = viewModel.currentMinPosePresenceConfidence,
                    currentDelegate = viewModel.currentDelegate,
                    poseLandmarkerHelperListener = this
                )
            }
        }

        binding.btnFinish.setOnClickListener {
            requireActivity().finish()
        }

        binding.fabSwitchCamera.setOnClickListener {
            cameraFacing = if (cameraFacing == CameraSelector.LENS_FACING_FRONT) {
                CameraSelector.LENS_FACING_BACK
            } else {
                CameraSelector.LENS_FACING_FRONT
            }
            bindCameraUseCases()
        }
    }

    private fun setUpCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider ?: throw IllegalStateException("Camera initialization failed.")
        val cameraSelector = CameraSelector.Builder().requireLensFacing(cameraFacing).build()
        preview = Preview.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(binding.viewFinder.display.rotation).build()
        imageAnalyzer = ImageAnalysis.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(binding.viewFinder.display.rotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888).build()
            .also { it.setAnalyzer(backgroundExecutor) { image -> detectPose(image) } }
        cameraProvider.unbindAll()
        try {
            camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
            preview?.setSurfaceProvider(binding.viewFinder.surfaceProvider)
        } catch (exc: Exception) { Log.e(TAG, "Use case binding failed", exc) }
    }

    private fun detectPose(imageProxy: ImageProxy) {
        if(this::poseLandmarkerHelper.isInitialized) {
            poseLandmarkerHelper.detectLiveStream(
                imageProxy = imageProxy,
                isFrontCamera = cameraFacing == CameraSelector.LENS_FACING_FRONT
            )
        }
    }

    private fun showHeightDialog() {
        val input = EditText(requireContext())
        input.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        input.hint = "例如: 170"

        AlertDialog.Builder(requireContext())
            .setTitle("準備開始")
            .setMessage("請輸入受測者的身高 (公分)：")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("確定") { _, _ ->
                val heightStr = input.text.toString()
                binding.overlay.userHeightCm = heightStr.toFloatOrNull() ?: 160f

                binding.viewFinder.post { setUpCamera() }
                currentState = State.WAITING_FOR_DISTANCE
                binding.tvCenterStatus.text = "請退後至\n大於 4 公尺處"
                binding.tvCenterStatus.setTextColor(Color.WHITE)
                binding.tvCenterStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 50f)
            }
            .show()
    }

    private fun startCountdownSequence() {
        currentState = State.COUNTDOWN
        lifecycleScope.launch(Dispatchers.Main) {
            binding.tvCenterStatus.textSize = 100f
            binding.tvCenterStatus.text = "3"
            delay(1000)
            binding.tvCenterStatus.text = "2"
            delay(1000)
            binding.tvCenterStatus.text = "1"
            delay(1000)
            binding.tvCenterStatus.text = "GO!"
            delay(800)
            binding.tvCenterStatus.text = ""
            currentState = State.WALKING
        }
    }

    override fun onResults(resultBundle: PoseLandmarkerHelper.ResultBundle) {
        if (!isTrainingStarted) return
        activity?.runOnUiThread {
            if (_binding != null) {
                val results = resultBundle.results.first()
                val currentDistance = binding.overlay.currentDistance

                when (currentState) {
                    State.WAITING_FOR_DISTANCE -> {
                        if (currentDistance >= 4.0f) startCountdownSequence()
                    }
                    State.WALKING -> processLogic(results)
                    else -> {}
                }

                binding.overlay.setPoseResults(results, resultBundle.inputImageHeight, resultBundle.inputImageWidth, RunningMode.LIVE_STREAM)
            }
        }
    }

    private fun processLogic(results: PoseLandmarkerResult) {
        if (isTestCompleted || results.landmarks().isEmpty() || currentState != State.WALKING) return

        val landmarks = results.landmarks()[0]

        // 1. 可見度檢查
        val requiredIndices = intArrayOf(11, 12, 23, 24, 27, 28, 29, 30, 31, 32)
        val isVisible = requiredIndices.all { landmarks[it].visibility().orElse(0f) > VISIBILITY_THRESHOLD }

        if (!isVisible) {
            binding.tvCenterStatus.text = "請確保全身入鏡"
            binding.tvCenterStatus.setTextColor(Color.RED)
            binding.tvCenterStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 40f)
            return
        }

        // 2. 姿勢判定
        val leftHeel = landmarks[29]; val rightHeel = landmarks[30]
        val leftToe = landmarks[31]; val rightToe = landmarks[32]
        val isPoseCorrect = when (currentPhaseMode) {
            Mode.TOE -> (leftHeel.y() < leftToe.y() - TOE_HEEL_Y_DIFF_THRESHOLD) && (rightHeel.y() < rightToe.y() - TOE_HEEL_Y_DIFF_THRESHOLD)
            Mode.HEEL -> (leftToe.y() < leftHeel.y() - TOE_HEEL_Y_DIFF_THRESHOLD) && (rightToe.y() < rightHeel.y() - TOE_HEEL_Y_DIFF_THRESHOLD)
        }

        // 3. 步數偵測 (修正：允許第一步偵測)
        val yDiff = leftHeel.y() - rightHeel.y()
        val currentLeadingFoot = when {
            yDiff > MIN_STEP_DISTANCE -> 0
            yDiff < -MIN_STEP_DISTANCE -> 1
            else -> lastLeadingFoot
        }

        // 修改：只要腳部狀態改變且不為空就計步
        if (currentLeadingFoot != -1 && currentLeadingFoot != lastLeadingFoot) {
            stepsInCurrentPhase++
            if (stepsInCurrentPhase >= STEPS_PER_PHASE) {
                moveToNextPhase()
                return // 進入下一階段，不再更新大字
            }
        }
        lastLeadingFoot = currentLeadingFoot

        // 4. 更新大字顯示資訊 (步數 + 模式)
        val phaseTitle = if (currentPhaseMode == Mode.TOE) "腳尖走路" else "腳跟走路"
        binding.tvCenterStatus.text = "$phaseTitle\n$stepsInCurrentPhase 步"
        binding.tvCenterStatus.setTextColor(if (isPoseCorrect) Color.WHITE else Color.RED)
        binding.tvCenterStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 80f)

        // 4. 準確率計算
        val shoulderBalance = 1.0f - abs(landmarks[11].y() - landmarks[12].y())
        val frameScore = (shoulderBalance * 0.7f + (if (isPoseCorrect) 0.3f else 0f)) * 100f
        totalBalanceScore += frameScore
        balanceTicks++

        val phaseText = if (currentPhaseMode == Mode.TOE) "腳尖走路" else "腳跟走路"
        val status = if (isPoseCorrect) "正在$phaseText..." else "請使用${phaseText}姿勢！"
        
        binding.overlay.updateTestInfo(stepsInCurrentPhase, currentSet, status, calculateAvgAccuracy(), isTestCompleted, "步數", 3)
    }

    private fun calculateAvgAccuracy() = if (balanceTicks == 0) 0f else (totalBalanceScore / balanceTicks).coerceIn(0f, 100f)

    private fun moveToNextPhase() {
        lastLeadingFoot = -1
        
        if (currentPhaseMode == Mode.TOE) {
            startTimer(SHORT_REST_MS, State.RESTING_SHORT, "休息中，準備換腳跟走路")
        } else {
            if (currentSet < TOTAL_SETS) {
                startTimer(LONG_REST_MS, State.RESTING_SET, "組間休息中")
            } else {
                completeTest()
            }
        }
    }

    private fun startTimer(durationMs: Long, targetState: State, message: String) {
        currentState = targetState
        timer?.cancel()
        timer = object : CountDownTimer(durationMs, 1000) {
            override fun onTick(ms: Long) {
                val sec = ms / 1000
                // 大字顯示休息資訊
                binding.tvCenterStatus.text = "休息中\n$sec 秒"
                binding.tvCenterStatus.setTextColor(Color.YELLOW)
                binding.tvCenterStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 80f)

                binding.overlay.updateTestInfo(stepsInCurrentPhase, currentSet, "$message ($sec s)", calculateAvgAccuracy(), isTestCompleted, "步數", 3)
            }
            override fun onFinish() {
                // 切換階段
                if (targetState == State.RESTING_SHORT) {
                    currentPhaseMode = Mode.HEEL
                } else if (targetState == State.RESTING_SET) {
                    currentSet++
                    currentPhaseMode = Mode.TOE
                }

                // 修正：休息結束後重置參數並要求回到 4 公尺
                stepsInCurrentPhase = 0
                lastLeadingFoot = -1
                currentState = State.WAITING_FOR_DISTANCE

                binding.tvCenterStatus.text = "請退後至\n大於 4 公尺處"
                binding.tvCenterStatus.setTextColor(Color.WHITE)
                binding.tvCenterStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 50f)
            }
        }.start()
    }

    private fun completeTest() {
        isTestCompleted = true
        val finalAccuracy = calculateAvgAccuracy()

        // --- 封包傳送 ---
        val result = ExerciseResult(
            exerciseName = "腳尖腳跟走路",
            exerciseId = "B-5",
            accuracy = finalAccuracy,
        )
        viewModel.postResult(result)

        binding.overlay.updateTestInfo(stepsInCurrentPhase, currentSet, "測試完成！", finalAccuracy, true, "步數", 3)
        binding.resultPanel.visibility = View.VISIBLE
        binding.tvFinalResult.text = String.format(Locale.US, "總平均準確率: %.1f%%", finalAccuracy)
    }

    override fun onResume() {
        super.onResume()
        if (isTrainingStarted) {
            backgroundExecutor.execute {
                if (poseLandmarkerHelper.isClose()) {
                    poseLandmarkerHelper.setupPoseLandmarker()
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        timer?.cancel()
        backgroundExecutor.execute { if(this::poseLandmarkerHelper.isInitialized) poseLandmarkerHelper.clearPoseLandmarker() }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
        backgroundExecutor.shutdown()
    }

    override fun onError(error: String, errorCode: Int) {
        activity?.runOnUiThread { Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show() }
    }
}
