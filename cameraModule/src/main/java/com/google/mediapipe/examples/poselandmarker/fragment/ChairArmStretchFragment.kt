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
import android.os.SystemClock
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
import android.speech.tts.TextToSpeech

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
    private lateinit var tts: TextToSpeech
    private var lastSpeakTime = 0L
    private var lastSpeechText = ""

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
        tts = TextToSpeech(requireContext()) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale.TAIWAN
            }
        }
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

    private fun speakOut(text: String, throttleMs: Long = 0L) {
        val currentTime = SystemClock.uptimeMillis()
        if (text != lastSpeechText || (currentTime - lastSpeakTime > throttleMs)) {
            if (::tts.isInitialized) {
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
            }
            lastSpeakTime = currentTime
            lastSpeechText = text
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
        if (isTestCompleted) return

        if (isRestingBetweenSets || isRelaxing) {
            binding.overlay.updateTestInfo(currentRep, currentSet, currentTimerStatusText, calculateAvgAccuracy(), isTestCompleted, "次數", 3)
            return
        }

        if (results.landmarks().isEmpty()) {
            if (!isPaused && isHolding) {
                timer?.cancel()
                isPaused = true
            }
            binding.overlay.updateTestInfo(currentRep, currentSet, "請進入畫面", calculateAvgAccuracy(), isTestCompleted, "次數", 3)
            speakOut("請進入畫面", 5000L)
            return
        }

        val landmarks = results.landmarks()[0]

        // 1. 可見度檢查：只需檢查 鼻子(0) 與 雙肩(11, 12)
        val requiredIndices = intArrayOf(0, 11, 12)
        val isVisible = requiredIndices.all { landmarks[it].visibility().orElse(0f) > VISIBILITY_THRESHOLD }

        if (!isVisible) {
            if (!isPaused && isHolding) {
                timer?.cancel()
                isPaused = true
            }
            binding.overlay.updateTestInfo(currentRep, currentSet, "請將頭部與雙肩放入畫面", calculateAvgAccuracy(), isTestCompleted, "次數", 3)
            speakOut("請進入畫面", 5000L)
            return
        } else if (isPaused) {
            isPaused = false
            if (isHolding) startHoldingTimer(millisRemaining)
        }

        val nose = landmarks[0]
        val leftShoulder = landmarks[11]
        val rightShoulder = landmarks[12]

        // 2. 核心判定邏輯：計算挺胸深度差
        // 計算雙肩的平均深度
        val avgShoulderZ = (leftShoulder.z() + rightShoulder.z()) / 2f
        // 算出肩膀與鼻子的深度差 (挺胸時，肩膀 Z 會比鼻子 Z 大很多)
        val zDiff = avgShoulderZ - nose.z()

        // 設定挺胸門檻值 (建議在 0.04 ~ 0.08 之間測試，0.05 是常用的靈敏門檻)
        val chestOutThreshold = 0.05f
        val isPostureCorrect = zDiff > chestOutThreshold

        // 3. 動作狀態機切換
        if (!isHolding && isPostureCorrect) {
            startHoldingTimer()
        }

        // 4. 準確度計算：挺胸越深 + 肩膀越水平 = 分數越高
        if (isHolding) {
            if (isPostureCorrect) {
                // 水平度：1.0 代表兩肩齊高
                val shoulderBalance = 1.0f - abs(leftShoulder.y() - rightShoulder.y()).coerceIn(0f, 1f)
                // 挺胸深度分數：zDiff 越高分數越高
                val depthScore = (zDiff / 0.1f).coerceIn(0f, 1f)

                val currentFrameAccuracy = (shoulderBalance * 40f + depthScore * 60f)
                totalAccuracyAccumulated += currentFrameAccuracy
            } else {
                totalAccuracyAccumulated += 30f // 姿勢跑掉時給予較低的保底分
                speakOut("請維持挺胸並抓住椅背", 3000L)
            }
            accuracyTicks++
        }

        val status = when {
            isHolding -> {
                if (!isPostureCorrect) "請維持挺胸！ ($currentTimerStatusText)"
                else currentTimerStatusText
            }
            else -> "請往後抓住椅背並挺胸"
        }

        binding.overlay.updateTestInfo(currentRep, currentSet, status, calculateAvgAccuracy(), isTestCompleted, "次數", 3)
    }

    private fun calculateAvgAccuracy() = if (accuracyTicks == 0) 0f else (totalAccuracyAccumulated / accuracyTicks).coerceIn(0f, 100f)

    private fun startHoldingTimer(durationMs: Long = HOLD_TIME_MS) {
        isHolding = true
        speakOut("請維持挺胸並抓住椅背")
        timer?.cancel()
        timer = object : CountDownTimer(durationMs, 1000) {
            override fun onTick(ms: Long) {
                millisRemaining = ms
                currentTimerStatusText = "挺胸維持中 (${ms/1000}s)"
                binding.overlay.updateTestInfo(currentRep, currentSet, currentTimerStatusText, calculateAvgAccuracy(), isTestCompleted, "次數", 3)
            }
            override fun onFinish() {
                isHolding = false
                startRelaxingTimer()
            }
        }.start()
    }

    private fun startRelaxingTimer(durationMs: Long = RELAX_TIME_MS) {
        isRelaxing = true
        speakOut("請放鬆")
        currentRep++ // 在進入放鬆階段時就增加次數
        timer?.cancel()
        currentTimerStatusText = "請放鬆 (${durationMs / 1000}s)"
        binding.overlay.updateTestInfo(currentRep, currentSet, currentTimerStatusText, calculateAvgAccuracy(), isTestCompleted, "次數", 3)

        timer = object : CountDownTimer(durationMs, 1000) {
            override fun onTick(ms: Long) {
                millisRemaining = ms
                currentTimerStatusText = "請放鬆 (${ms / 1000}s)"
                binding.overlay.updateTestInfo(currentRep, currentSet, currentTimerStatusText, calculateAvgAccuracy(), isTestCompleted, "次數", 3)
            }
            override fun onFinish() {
                isRelaxing = false
                if (currentRep >= TOTAL_REPS_PER_SET) {
                    if (currentSet < TOTAL_SETS) startSetRestTimer() else completeTest()
                } else {
                    currentTimerStatusText = "請往後抓住椅背並挺胸"
                }
            }
        }.start()
    }

    private fun startSetRestTimer(durationMs: Long = SET_REST_TIME_MS) {
        isRestingBetweenSets = true
        speakOut("休息時間")
        timer?.cancel()
        currentTimerStatusText = "組間休息 (${durationMs / 1000}s)"
        binding.overlay.updateTestInfo(currentRep, currentSet, currentTimerStatusText, calculateAvgAccuracy(), isTestCompleted, "次數", 3)

        timer = object : CountDownTimer(durationMs, 1000) {
            override fun onTick(ms: Long) {
                millisRemaining = ms
                currentTimerStatusText = "組間休息 (${ms / 1000}s)"
                binding.overlay.updateTestInfo(currentRep, currentSet, currentTimerStatusText, calculateAvgAccuracy(), isTestCompleted, "次數", 3)
            }
            override fun onFinish() {
                isRestingBetweenSets = false
                currentSet++
                currentRep = 0
                currentTimerStatusText = "請往後抓住椅背並挺胸"
            }
        }.start()
    }

    private fun completeTest() {
        isTestCompleted = true
        speakOut("測試完成")
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
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
        _binding = null
        super.onDestroyView()
        backgroundExecutor.shutdown()
    }

    override fun onError(error: String, errorCode: Int) {
        activity?.runOnUiThread { Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show() }
    }

    private lateinit var backgroundExecutor: ExecutorService
}
