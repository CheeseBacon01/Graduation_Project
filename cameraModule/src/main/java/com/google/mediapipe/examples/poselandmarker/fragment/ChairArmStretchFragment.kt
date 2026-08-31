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
import com.google.mediapipe.examples.poselandmarker.databinding.FragmentChairArmStretchBinding
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import com.google.mediapipe.examples.poselandmarker.ExerciseResult

class ChairArmStretchFragment : Fragment(), PoseLandmarkerHelper.LandmarkerListener {

    companion object {
        private const val TAG = "ChairArmStretch"
        private const val HOLD_TIME_MS = 10000L // 維持 10 秒
        private const val RELAX_TIME_MS = 5000L // 放鬆 5 秒
        private const val TOTAL_REPS_PER_SET = 3
        private const val TOTAL_SETS = 3
        private const val SET_REST_TIME_MS = 30000L
        private const val VISIBILITY_THRESHOLD = 0.5f
    }

    private var _binding: FragmentChairArmStretchBinding? = null
    private val binding get() = _binding!!

    private lateinit var poseLandmarkerHelper: PoseLandmarkerHelper
    private val viewModel: MainViewModel by activityViewModels()
    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraFacing = CameraSelector.LENS_FACING_FRONT

    // Test Variables
    private var currentRep = 0
    private var currentSet = 1
    private var isHolding = false
    private var isRelaxing = false
    private var isRestingBetweenSets = false
    private var isTestCompleted = false
    private var isTrainingStarted = false
    
    private var timer: CountDownTimer? = null
    private var totalAccuracyAccumulated = 0f
    private var accuracyTicks = 0
    private var currentTimerStatusText = ""
    private var millisRemaining: Long = 0L
    private var isPaused: Boolean = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentChairArmStretchBinding.inflate(inflater, container, false)
        return binding.root
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        backgroundExecutor = Executors.newSingleThreadExecutor()

        binding.btnStartTraining.setOnClickListener {
            isTrainingStarted = true
            binding.setupPanel.visibility = View.GONE
            
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

    override fun onResults(resultBundle: PoseLandmarkerHelper.ResultBundle) {
        if (!isTrainingStarted) return
        activity?.runOnUiThread {
            if (_binding != null) {
                val results = resultBundle.results.first()
                processLogic(results)
                binding.overlay.setPoseResults(
                    results, resultBundle.inputImageHeight, resultBundle.inputImageWidth, RunningMode.LIVE_STREAM
                )
            }
        }
    }

    private fun processLogic(results: PoseLandmarkerResult) {
        if (isTestCompleted || results.landmarks().isEmpty()) return

        val landmarks = results.landmarks()[0]

        // 1. 可見度檢查 (只需肩膀，因為手腕往後伸時常被椅背擋住)
        val requiredIndices = intArrayOf(11, 12)
        val isVisible = requiredIndices.all { landmarks[it].visibility().orElse(0f) > VISIBILITY_THRESHOLD }

        if (!isVisible) {
            if (!isPaused && (isHolding || isRelaxing || isRestingBetweenSets)) {
                timer?.cancel()
                isPaused = true
            }
            binding.overlay.updateTestInfo(currentRep, currentSet, "請面對鏡頭並將雙肩放入畫面", calculateAvgAccuracy(), isTestCompleted, "次數")
            return
        } else if (isPaused) {
            isPaused = false
            when {
                isHolding -> startHoldingTimer(millisRemaining)
                isRelaxing -> startRelaxingTimer(millisRemaining)
                isRestingBetweenSets -> startSetRestTimer(millisRemaining)
            }
        }

        val leftShoulder = landmarks[11]; val rightShoulder = landmarks[12]
        val leftWrist = landmarks[15]; val rightWrist = landmarks[16]

        // 2. 判定姿勢：
        // 手部必須在肩膀後面 (z 軸) 且低於肩膀 (y 軸)。
        // 當手腕被椅背擋住時，我們同時參考手肘 (13, 14) 的預測位置。
        val zThreshold = 0.05f

        // 判斷左/右手臂（手腕或手肘）是否向後伸
        val leftArmBack = leftWrist.z() > leftShoulder.z() + zThreshold ||
                landmarks[13].z() > leftShoulder.z() + zThreshold
        val rightArmBack = rightWrist.z() > rightShoulder.z() + zThreshold ||
                landmarks[14].z() > rightShoulder.z() + zThreshold

        val isWristsLow = leftWrist.y() > leftShoulder.y() &&
                rightWrist.y() > rightShoulder.y()

        val isPostureCorrect = leftArmBack && rightArmBack && isWristsLow

        if (!isHolding && !isRelaxing && !isRestingBetweenSets && isPostureCorrect) {
            startHoldingTimer()
        }

        if (isHolding) {
            if (isPostureCorrect) {
                val shoulderBalance = 1.0f - abs(leftShoulder.y() - rightShoulder.y())
                totalAccuracyAccumulated += (shoulderBalance * 100f).coerceIn(0f, 100f)
            } else {
                totalAccuracyAccumulated += 0f
            }
            accuracyTicks++
        }

        val status = when {
            isRestingBetweenSets || isHolding || isRelaxing -> {
                if (isHolding && !isPostureCorrect) "請維持挺胸並抓住椅背！"
                else currentTimerStatusText
            }
            else -> "請往後抓住椅背並挺胸"
        }

        binding.overlay.updateTestInfo(currentRep, currentSet, status, calculateAvgAccuracy(), isTestCompleted, "次數", 3)
    }

    private fun calculateAvgAccuracy() = if (accuracyTicks == 0) 0f else (totalAccuracyAccumulated / accuracyTicks).coerceIn(0f, 100f)

    private fun startHoldingTimer(durationMs: Long = HOLD_TIME_MS) {
        isHolding = true
        timer?.cancel()
        timer = object : CountDownTimer(durationMs, 1000) {
            override fun onTick(ms: Long) {
                millisRemaining = ms
                currentTimerStatusText = "挺胸維持中 (${ms/1000}s)"
            }
            override fun onFinish() {
                isHolding = false
                startRelaxingTimer()
            }
        }.start()
    }

    private fun startRelaxingTimer(durationMs: Long = RELAX_TIME_MS) {
        isRelaxing = true
        currentRep++ // 在進入放鬆階段時就增加次數
        timer?.cancel()
        timer = object : CountDownTimer(durationMs, 1000) {
            override fun onTick(ms: Long) {
                millisRemaining = ms
                currentTimerStatusText = "請放鬆 (${ms/1000}s)"
            }
            override fun onFinish() {
                isRelaxing = false
                if (currentRep >= TOTAL_REPS_PER_SET) {
                    if (currentSet < TOTAL_SETS) startSetRestTimer() else completeTest()
                }
            }
        }.start()
    }

    private fun startSetRestTimer(durationMs: Long = SET_REST_TIME_MS) {
        isRestingBetweenSets = true
        timer?.cancel()
        timer = object : CountDownTimer(durationMs, 1000) {
            override fun onTick(ms: Long) {
                millisRemaining = ms
                currentTimerStatusText = "組間休息 (${ms/1000}s)"
            }
            override fun onFinish() {
                isRestingBetweenSets = false
                currentSet++
                currentRep = 0
            }
        }.start()
    }

    private fun completeTest() {
        isTestCompleted = true
        val finalAccuracy = calculateAvgAccuracy()
        // --- 封包傳送 ---
        val result = ExerciseResult(
            exerciseName = "椅上伸展手臂",
            exerciseId = "B-6",
            accuracy = finalAccuracy,
        )
        viewModel.postResult(result)
        binding.overlay.updateTestInfo(currentRep, currentSet, "測試完成！", finalAccuracy, true, "次數", 3)
        binding.resultPanel.visibility = View.VISIBLE
        binding.tvFinalResult.text = String.format(Locale.US, "總平均準確率: %.1f%%", finalAccuracy)
    }

    override fun onResume() {
        super.onResume()
        // Start the PoseLandmarkerHelper again when users come back
        // to the foreground.
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
        if (this::poseLandmarkerHelper.isInitialized) {
            backgroundExecutor.execute { poseLandmarkerHelper.clearPoseLandmarker() }
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
        backgroundExecutor.shutdown()
    }

    override fun onError(error: String, errorCode: Int) {
        activity?.runOnUiThread { Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show() }
    }

    private lateinit var backgroundExecutor: ExecutorService
}
