package com.google.mediapipe.examples.poselandmarker.fragment

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Matrix
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

import com.google.mediapipe.examples.handlandmarker.HandLandmarkerHelper
import com.google.mediapipe.examples.poselandmarker.MainViewModel
import com.google.mediapipe.examples.poselandmarker.R
import com.google.mediapipe.examples.poselandmarker.databinding.FragmentWringTowelBinding
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark

import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt
import com.google.mediapipe.examples.poselandmarker.ExerciseResult

class WringTowelFragment : Fragment() {

    companion object {
        private const val TAG = "WringTowel"
        private const val REPS_PER_SET = 12
        private const val TOTAL_SETS = 3 // 共 3 組
        private const val TARGET_HOLD_MS = 3000L // 要求持續緊握的秒數 (3秒)
        private const val SET_REST_TIME_MS = 60000L // 組間休息 60 秒

        // --- 握力判定門檻 (已針對「指尖到手腕」的比例調整) ---
        // 握拳時，指尖會捲縮靠近手腕，比例會變小。
        // 攤開時，指尖遠離手腕，比例通常會大於 2.0。
        private const val SQUEEZE_THRESHOLD = 0.90f // 比例低於此值視為握緊
        private const val RELEASE_THRESHOLD = 1.00f // 比例高於此值視為鬆開
    }

    // 簡化後的動作狀態機
    enum class TwistState {
        IDLE,           // 等待雙手握拳
        HOLDING,        // 已握拳，開始倒數並計算準確率
        WAITING_RELEASE // 倒數完成，等待手部攤開
    }

    private var _binding: FragmentWringTowelBinding? = null
    private val binding get() = _binding!!

    private lateinit var handLandmarkerHelper: HandLandmarkerHelper

    private val viewModel: MainViewModel by activityViewModels()
    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraFacing = CameraSelector.LENS_FACING_FRONT

    // 訓練狀態
    private var currentRep = 0
    private var currentSet = 1
    private var currentState = TwistState.IDLE // 初始化狀態
    private var isResting = false
    private var isTestCompleted = false
    private var isTrainingStarted = false

    private var twistStartTime = 0L
    private var timer: CountDownTimer? = null

    // 準確率評估基準
    private var totalAccuracyAccumulated = 0f
    private var accuracyTicks = 0

    private lateinit var backgroundExecutor: ExecutorService

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentWringTowelBinding.inflate(inflater, container, false)
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
                handLandmarkerHelper = HandLandmarkerHelper(
                    context = requireContext(),
                    runningMode = RunningMode.LIVE_STREAM,
                    maxNumHands = 2, // 同時看雙手
                    handLandmarkerHelperListener = handListener
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
            .also { it.setAnalyzer(backgroundExecutor) { image -> processImageProxy(image) } }

        cameraProvider.unbindAll()
        try {
            camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
            preview?.setSurfaceProvider(binding.viewFinder.surfaceProvider)
        } catch (exc: Exception) { Log.e(TAG, "Use case binding failed", exc) }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun processImageProxy(imageProxy: ImageProxy) {
        val frameTime = SystemClock.uptimeMillis()
        val isFrontCamera = cameraFacing == CameraSelector.LENS_FACING_FRONT

        val bitmapBuffer = Bitmap.createBitmap(imageProxy.width, imageProxy.height, Bitmap.Config.ARGB_8888)
        imageProxy.use { bitmapBuffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer) }
        imageProxy.close()

        val matrix = Matrix().apply {
            postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
            if (isFrontCamera) postScale(-1f, 1f, imageProxy.width.toFloat(), imageProxy.height.toFloat())
        }
        val rotatedBitmap = Bitmap.createBitmap(bitmapBuffer, 0, 0, bitmapBuffer.width, bitmapBuffer.height, matrix, true)
        val mpImage = BitmapImageBuilder(rotatedBitmap).build()

        if (this::handLandmarkerHelper.isInitialized && !handLandmarkerHelper.isClose()) {
            handLandmarkerHelper.detectAsync(mpImage, frameTime)
        }
    }

    private fun getDist(lm1: NormalizedLandmark, lm2: NormalizedLandmark): Float {
        return sqrt((lm1.x() - lm2.x()).pow(2) + (lm1.y() - lm2.y()).pow(2))
    }

    /**
     * 計算擠壓程度：四指指尖到手腕的平均距離 / 手掌大小 (手腕到中指基部)
     * 比例越小代表指尖越靠近手腕（握得越緊）。
     */
    private fun calculateSqueezeRatio(landmarks: List<NormalizedLandmark>): Float {
        val fingerTips = listOf(landmarks[8], landmarks[12], landmarks[16], landmarks[20])
        val wrist = landmarks[0]
        val middleMCP = landmarks[9]

        // 計算手掌大小作為基準距離 (腕部到中指基部)
        val handSize = getDist(wrist, middleMCP)

        // 計算四指指尖到「手腕」的平均距離
        val avgDistToWrist = fingerTips.map { getDist(wrist, it) }.average().toFloat()

        return avgDistToWrist / handSize
    }

    private val handListener = object : HandLandmarkerHelper.LandmarkerListener {
        override fun onError(error: String, errorCode: Int) { showErrorMsg(error) }
        override fun onResults(resultBundle: HandLandmarkerHelper.ResultBundle) {
            if (!isTrainingStarted) return
            activity?.runOnUiThread {
                if (_binding != null) {
                    val results = resultBundle.results.firstOrNull() ?: return@runOnUiThread
                    processLogic(results)
                    binding.overlay.setHandResults(results, resultBundle.inputImageHeight, resultBundle.inputImageWidth, RunningMode.LIVE_STREAM)
                }
            }
        }
    }

    private fun processLogic(results: HandLandmarkerResult) {
        if (isTestCompleted || isResting) return

        val landmarksList = results.landmarks()

        // 1. 防呆：必須偵測到雙手在畫面中
        if (landmarksList.size < 2) {
            if (currentState == TwistState.HOLDING || currentState == TwistState.WAITING_RELEASE) {
                currentState = TwistState.IDLE
            }
            binding.overlay.updateTestInfo(currentRep, currentSet, "請將雙手平舉放入畫面", calculateAvgAccuracy(), isTestCompleted, "次數", TOTAL_SETS, showAccuracy = true)
            return
        }

        val hand1 = landmarksList[0]
        val hand2 = landmarksList[1]

        // 2. 測量當前的握拳狀態 (計算雙手的擠壓比例)
        val ratio1 = calculateSqueezeRatio(hand1)
        val ratio2 = calculateSqueezeRatio(hand2)

        // 雙手的比例都低於握緊門檻，才算握拳
        val isGripped = ratio1 < SQUEEZE_THRESHOLD && ratio2 < SQUEEZE_THRESHOLD

        // 雙手的比例都高於鬆開門檻，才算完全攤開鬆手
        val isReleased = ratio1 > RELEASE_THRESHOLD && ratio2 > RELEASE_THRESHOLD

        // 4. 準確率計算邏輯 (參考 ChairArmStretch)
        // 只要在倒數中 (HOLDING)，就會逐幀計算準確率。如果有緊握就是滿分，鬆開就是 0 分。
        if (currentState == TwistState.HOLDING) {
            if (!isReleased) {
                totalAccuracyAccumulated += 100f
            } else {
                totalAccuracyAccumulated += 0f
            }
            accuracyTicks++
        }

        var status = ""

        // 5. 狀態機邏輯
        when (currentState) {
            TwistState.IDLE -> {
                if (isGripped) {
                    // 偵測到雙手緊握，立刻開始倒數
                    currentState = TwistState.HOLDING
                    twistStartTime = SystemClock.uptimeMillis()
                    status = "保持緊握..."
                } else {
                    status = "請雙手握拳並扭毛巾"
                }
            }

            TwistState.HOLDING -> {
                val holdTime = SystemClock.uptimeMillis() - twistStartTime
                val remainingSec = max(0f, (TARGET_HOLD_MS - holdTime) / 1000f)

                if (holdTime >= TARGET_HOLD_MS) {
                    // 達到 3 秒，進入等待鬆開的狀態
                    currentState = TwistState.WAITING_RELEASE
                } else {
                    // 倒數中
                    if (isReleased) {
                        status = String.format(Locale.US, "請維持雙手緊握！(%.1f s)", remainingSec)
                    } else {
                        status = String.format(Locale.US, "保持緊握... %.1f s", remainingSec)
                    }
                }
            }

            TwistState.WAITING_RELEASE -> {
                status = "請完全鬆開雙手"
                // 只要雙手確實攤開 (isReleased) 就算完成這一次
                if (isReleased) {
                    currentState = TwistState.IDLE
                    finishRep()
                }
            }
        }

        binding.overlay.updateTestInfo(currentRep, currentSet, status, calculateAvgAccuracy(), isTestCompleted, "次數", TOTAL_SETS, showAccuracy = true)
    }

    private fun finishRep() {
        // totalAccuracyAccumulated 和 accuracyTicks 已經在 processLogic 逐幀計算，此處只需進入下一次或休息
        currentRep++

        if (currentRep >= REPS_PER_SET) {
            if (currentSet < TOTAL_SETS) {
                startRestTimer()
            } else {
                completeTest()
            }
        }
    }

    private fun calculateAvgAccuracy() = if (accuracyTicks == 0) 0f else (totalAccuracyAccumulated / accuracyTicks).coerceIn(0f, 100f)

    private fun startRestTimer() {
        isResting = true
        timer?.cancel()
        timer = object : CountDownTimer(SET_REST_TIME_MS, 1000) {
            override fun onTick(ms: Long) {
                val currentTimerStatusText = "組間休息 (${ms/1000}s)"
                binding.overlay.updateTestInfo(currentRep, currentSet, currentTimerStatusText, calculateAvgAccuracy(), isTestCompleted, "次數", TOTAL_SETS)
            }
            override fun onFinish() {
                isResting = false
                currentSet++
                currentRep = 0
                currentState = TwistState.IDLE
            }
        }.start()
    }

    private fun completeTest() {
        isTestCompleted = true
        val finalAccuracy = calculateAvgAccuracy()
        // --- 封包傳送 ---
        val result = ExerciseResult(
            exerciseName = "扭毛巾",
            exerciseId = "C-2,D-2",
            reps = currentRep,
            sets = currentSet,
            accuracy = finalAccuracy
        )
        viewModel.postResult(result)

        binding.overlay.updateTestInfo(currentRep, currentSet, "測試完成！", finalAccuracy, true, "次數", TOTAL_SETS)
        binding.resultPanel.visibility = View.VISIBLE
        binding.tvFinalResult.text = String.format(Locale.US, "總平均準確率: %.1f%%", finalAccuracy)
    }

    private fun showErrorMsg(error: String) {
        activity?.runOnUiThread { Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show() }
    }

    override fun onResume() {
        super.onResume()
        if (isTrainingStarted) {
            backgroundExecutor.execute {
                if (this::handLandmarkerHelper.isInitialized && handLandmarkerHelper.isClose()) {
                    handLandmarkerHelper.setupHandLandmarker()
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        timer?.cancel()
        if (this::handLandmarkerHelper.isInitialized) {
            backgroundExecutor.execute { handLandmarkerHelper.clearHandLandmarker() }
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
        backgroundExecutor.shutdown()
    }
}