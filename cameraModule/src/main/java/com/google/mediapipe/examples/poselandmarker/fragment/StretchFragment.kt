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
import com.google.mediapipe.examples.poselandmarker.databinding.FragmentStretchBinding
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.atan2
import com.google.mediapipe.examples.poselandmarker.ExerciseResult
class StretchFragment : Fragment(), PoseLandmarkerHelper.LandmarkerListener {

    companion object {
        private const val TAG = "StretchFragment"
        private const val HOLD_TIME_MS = 10000L // 維持 10 秒 V
        private const val RELAX_TIME_MS = 5000L // 放鬆 5 秒 V
        private const val TOTAL_REPS_PER_SET = 3
        private const val TOTAL_SETS = 3
//        private const val TOTAL_SETS = 1
        private const val SET_REST_TIME_MS = 30000L
        private const val ANGLE_THRESHOLD = 150.0 // 手臂拉直角度門檻
        private const val VISIBILITY_THRESHOLD = 0.5f
    }

    private var _binding: FragmentStretchBinding? = null
    private val binding get() = _binding!!

    private lateinit var poseLandmarkerHelper: PoseLandmarkerHelper
    private val viewModel: MainViewModel by activityViewModels()
    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraFacing = CameraSelector.LENS_FACING_FRONT

    // Stretch Test Variables
    private var currentRep = 0
    private var currentSet = 1
    private var isHolding = false
    private var isRelaxing = false
    private var isRestingBetweenSets = false
    private var isTestCompleted = false
    private var isTrainingStarted = false
    
    private var timer: CountDownTimer? = null
    private var totalScoreAccumulated = 0f
    private var scoreTicks = 0
    private var currentTimerStatusText = ""
    private var millisRemaining: Long = 0L
    private var isPaused: Boolean = false

    private lateinit var backgroundExecutor: ExecutorService

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentStretchBinding.inflate(inflater, container, false)
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
                processStretchLogic(results)
                binding.overlay.setPoseResults(
                    results, resultBundle.inputImageHeight, resultBundle.inputImageWidth, RunningMode.LIVE_STREAM
                )
            }
        }
    }

    private fun processStretchLogic(results: PoseLandmarkerResult) {
        if (isTestCompleted || results.landmarks().isEmpty()) return

        val landmarks = results.landmarks()[0]
        
        // 1. 動態可見度檢查
        // 尚未開始或是休息時只需肩膀 (11, 12)
        // 維持姿勢時 (isHolding) 需肩膀、手肘與手腕 (11, 12, 13, 14, 15, 16)
        val requiredIndices = if (isHolding) intArrayOf(11, 12, 13, 14, 15, 16) else intArrayOf(11, 12)
        val isVisible = requiredIndices.all { landmarks[it].visibility().orElse(0f) > VISIBILITY_THRESHOLD }
        
        if (!isVisible) {
            // 如果在執行途中離開畫面，暫停秒數
            if (!isPaused && (isHolding || isRelaxing || isRestingBetweenSets)) {
                timer?.cancel()
                isPaused = true
            }
            val statusMsg = if (isHolding) "請將雙肩、手肘與手腕放入畫面" else "請將雙肩放入畫面"
            binding.overlay.updateTestInfo(currentRep, currentSet, statusMsg, calculateAvgAccuracy(), isTestCompleted, "次數")
            return
        } else {
            // 回到畫面後繼續秒數
            if (isPaused) {
                isPaused = false
                when {
                    isHolding -> startHoldingTimer(millisRemaining)
                    isRelaxing -> startRelaxingTimer(millisRemaining)
                    isRestingBetweenSets -> startSetRestTimer(millisRemaining)
                }
            }
        }

        val leftShoulder = landmarks[11]; val leftElbow = landmarks[13]; val leftWrist = landmarks[15]
        val rightShoulder = landmarks[12]; val rightElbow = landmarks[14]; val rightWrist = landmarks[16]

        // 檢查手肘與手腕是否偵測到 (用於後續角度計算)
        val armsDetected = intArrayOf(13, 14, 15, 16).all { landmarks[it].visibility().orElse(0f) > 0.3f }

        val leftArmAngle = if (armsDetected) calculateAngle(leftShoulder, leftElbow, leftWrist) else 0.0
        val rightArmAngle = if (armsDetected) calculateAngle(rightShoulder, rightElbow, rightWrist) else 0.0
        
        val handsAboveHead = armsDetected && leftWrist.y() < leftShoulder.y() && rightWrist.y() < rightShoulder.y()
        val armsStraight = armsDetected && leftArmAngle > ANGLE_THRESHOLD && rightArmAngle > ANGLE_THRESHOLD

        // 如果不在任何計時狀態且偵測到正確姿勢，才啟動維持計時
        if (!isHolding && !isRelaxing && !isRestingBetweenSets && handsAboveHead && armsStraight) {
            startHoldingTimer()
        }

        var status = if (isHolding || isRelaxing || isRestingBetweenSets) {
            currentTimerStatusText
        } else {
            "請向上伸展並拉直手臂"
        }

        if (isHolding) {
            if (handsAboveHead && armsStraight) {
                val currentAccuracy = ((leftArmAngle + rightArmAngle) / 2.0 / 180.0 * 100.0).toFloat().coerceIn(0f, 100f)
                totalScoreAccumulated += currentAccuracy
            } else {
                // 維持姿勢時放下手臂：提醒、秒數持續倒數、計為 0 分 (扣分)
                status = "手臂請維持拉直高舉！(${currentTimerStatusText.filter { it.isDigit() }}s)"
                totalScoreAccumulated += 0f
            }
            scoreTicks++
        }
        
        binding.overlay.updateTestInfo(currentRep, currentSet, status, calculateAvgAccuracy(), isTestCompleted, "次數")
    }

    private fun calculateAvgAccuracy() = if (scoreTicks == 0) 0f else totalScoreAccumulated / scoreTicks

    private fun calculateAngle(a: com.google.mediapipe.tasks.components.containers.NormalizedLandmark, 
                               b: com.google.mediapipe.tasks.components.containers.NormalizedLandmark, 
                               c: com.google.mediapipe.tasks.components.containers.NormalizedLandmark): Double {
        val ang = Math.toDegrees(
            (atan2(c.y() - b.y(), c.x() - b.x()) - atan2(a.y() - b.y(), a.x() - b.x())).toDouble()
        )
        return abs(if (ang > 180) ang - 360 else if (ang < -180) ang + 360 else ang)
    }

    private fun startHoldingTimer(durationMs: Long = HOLD_TIME_MS) {
        isHolding = true
        timer?.cancel()
        timer = object : CountDownTimer(durationMs, 1000) {
            override fun onTick(ms: Long) {
                millisRemaining = ms
                currentTimerStatusText = "維持姿勢 (${ms/1000}s)"
            }
            override fun onFinish() {
                isHolding = false
                startRelaxingTimer()
            }
        }.start()
    }

    private fun startRelaxingTimer(durationMs: Long = RELAX_TIME_MS) {
        isRelaxing = true
        timer?.cancel()
        timer = object : CountDownTimer(durationMs, 1000) {
            override fun onTick(ms: Long) {
                millisRemaining = ms
                currentTimerStatusText = "請放鬆手臂 (${ms/1000}s)"
            }
            override fun onFinish() {
                isRelaxing = false
                currentRep++
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
            exerciseName = "手臂伸展",
            exerciseId = "A-7,B-7,C-8,D-8",
            accuracy = finalAccuracy,
        )
        viewModel.postResult(result)
        binding.overlay.updateTestInfo(currentRep, currentSet, "測試完成！", finalAccuracy, true, "次數")
        binding.resultPanel.visibility = View.VISIBLE
        binding.tvFinalResult.text = String.format(Locale.US, "總平均準確率: %.1f%%", finalAccuracy)
    }

    override fun onResume() {
        super.onResume()
        if (isTrainingStarted) {
            // Start the PoseLandmarkerHelper again when users come back
            // to the foreground.
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
        backgroundExecutor.execute { poseLandmarkerHelper.clearPoseLandmarker() }
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
