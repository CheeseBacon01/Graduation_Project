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
import com.google.mediapipe.examples.objectdetection.ObjectDetectorHelper
import com.google.mediapipe.examples.poselandmarker.MainViewModel
import com.google.mediapipe.examples.poselandmarker.R
import com.google.mediapipe.examples.poselandmarker.databinding.FragmentSqueezeBallBinding
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult

import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.sqrt
import kotlin.math.pow
import com.google.mediapipe.examples.poselandmarker.ExerciseResult

class SqueezeBallFragment : Fragment() {

    companion object {
        private const val TAG = "SqueezeBall"
        private const val REPS_PER_SET = 12
        private const val SETS_PER_HAND = 3
        private const val SET_REST_TIME_MS = 60000L
        private const val SQUEEZE_THRESHOLD = 0.60f // 比例低於此值視為握緊
        private const val RELEASE_THRESHOLD = 0.70f // 比例高於此值視為鬆開
        private const val PERFECT_SQUEEZE_RATIO = 0.50f // 完美握緊的比例 (指尖幾乎貼到大拇指)
        private const val TARGET_HOLD_MS = 2000L // 目標停留時間 (2秒)
    }
    private var minSqueezeRatioDuringHold = 1.0f
    private var _binding: FragmentSqueezeBallBinding? = null
    private val binding get() = _binding!!

    private lateinit var handLandmarkerHelper: HandLandmarkerHelper
    private lateinit var objectDetectorHelper: ObjectDetectorHelper

    private val viewModel: MainViewModel by activityViewModels()
    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraFacing = CameraSelector.LENS_FACING_FRONT

    // 訓練狀態
    private var currentRep = 0
    private var currentSet = 1
    private var isRightHand = true // 從右手開始
    private var isSqueezing = false
    private var isResting = false
    private var isTestCompleted = false
    private var isTrainingStarted = false

    // --- 握力球追蹤標記 (使用終極鎖定法) ---
    private var isBallDetected = false

    private var timer: CountDownTimer? = null
    private var totalAccuracyAccumulated = 0f
    private var accuracyTicks = 0
    private var currentTimerStatusText = ""

    private var squeezeStartTime = 0L

    private lateinit var backgroundExecutor: ExecutorService

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSqueezeBallBinding.inflate(inflater, container, false)
        return binding.root
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        backgroundExecutor = Executors.newSingleThreadExecutor()

        binding.btnStartTraining.setOnClickListener {
            binding.setupPanel.visibility = View.GONE
            isTrainingStarted = true
            binding.viewFinder.post { setUpCamera() }

            backgroundExecutor.execute {
                handLandmarkerHelper = HandLandmarkerHelper(
                    context = requireContext(),
                    runningMode = RunningMode.LIVE_STREAM,
                    handLandmarkerHelperListener = handListener
                )
                objectDetectorHelper = ObjectDetectorHelper(
                    context = requireContext(),
                    threshold = 0.2f, // 降低底層過濾門檻
                    runningMode = RunningMode.LIVE_STREAM,
                    objectDetectorListener = objectListener
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
        if (this::objectDetectorHelper.isInitialized && !objectDetectorHelper.isClosed()) {
            objectDetectorHelper.detectAsync(mpImage, frameTime)
        }
    }

    // --- Object Detector Listener ---
    private val objectListener = object : ObjectDetectorHelper.DetectorListener {
        override fun onError(error: String, errorCode: Int) { showErrorMsg(error) }
        override fun onResults(resultBundle: ObjectDetectorHelper.ResultBundle) {
            val results = resultBundle.results.firstOrNull()

            var foundBall = false
            results?.detections()?.forEach { detection ->
                if (detection.categories().any { it.categoryName() == "sports ball" && it.score() > 0.001f }) {
                    foundBall = true
                }
            }

            // 【終極鎖定】：只要有看到球，就鎖死變數。
            // 就算手遮住球導致 foundBall 變為 false，我們也「不把 isBallDetected 變回 false」，維持鎖定狀態！
            if (foundBall) {
                isBallDetected = true
            }
        }
    }

    // --- Hand Landmarker Listener ---
    private val handListener = object : HandLandmarkerHelper.LandmarkerListener {
        override fun onError(error: String, errorCode: Int) { showErrorMsg(error) }
        override fun onResults(resultBundle: HandLandmarkerHelper.ResultBundle) {
            activity?.runOnUiThread {
                if (_binding != null && isTrainingStarted) {
                    val results = resultBundle.results.firstOrNull() ?: return@runOnUiThread
                    processLogic(results)
                    binding.overlay.setHandResults(results, resultBundle.inputImageHeight, resultBundle.inputImageWidth, RunningMode.LIVE_STREAM)
                }
            }
        }
    }

    private fun processLogic(results: HandLandmarkerResult) {
        if (isTestCompleted || isResting) return

        val landmarks = results.landmarks().firstOrNull()

        // 1. 手離開畫面防呆機制 (重置鎖定)
        if (landmarks == null) {
            isBallDetected = false // 手不見了，強制重置球的偵測
            binding.overlay.isTrackingBall = false // 通知 UI 關閉紅框
            binding.overlay.updateTestInfo(currentRep, currentSet, "請將手放入畫面", calculateAvgAccuracy(), isTestCompleted, "次數", SETS_PER_HAND)
            return
        }

        val handedness = results.handedness().firstOrNull()?.firstOrNull()?.categoryName() // "Left" or "Right"
        val expectedHand = if (isRightHand) "Right" else "Left"

        // 2. 檢查是否偵測到正確的手
        if (handedness != expectedHand) {
            val msg = if (isRightHand) "請使用右手" else "請使用左手"
            binding.overlay.isTrackingBall = false // 錯誤的手不畫框
            binding.overlay.updateTestInfo(currentRep, currentSet, msg, calculateAvgAccuracy(), isTestCompleted, "次數", SETS_PER_HAND)
            return
        }

        // --- 將鎖定狀態傳給 OverlayView 畫出紅色追蹤框 ---
        binding.overlay.isTrackingBall = isBallDetected

        // 3. 判斷是否有球
        if (!isBallDetected) {
            binding.overlay.updateTestInfo(currentRep, currentSet, "請手上拿一顆球", calculateAvgAccuracy(), isTestCompleted, "次數", SETS_PER_HAND)
            return
        }

        // 4. 計算擠壓程度 (大拇指尖與其他指尖的平均距離)
        val thumbTip = landmarks[4]
        val fingerTips = listOf(landmarks[8], landmarks[12], landmarks[16], landmarks[20])
        val wrist = landmarks[0]
        val middleMCP = landmarks[9]

        // 使用手掌大小作為基準距離 (腕部到中指基部)
        val handSize = dist(wrist, middleMCP)
        val avgDistToThumb = fingerTips.map { dist(thumbTip, it) }.average().toFloat()
        val squeezeRatio = avgDistToThumb / handSize

        // 5. 動作判定
        // 5. 動作判定與專業評分
        if (!isSqueezing) {
            if (squeezeRatio < SQUEEZE_THRESHOLD) {
                isSqueezing = true
                squeezeStartTime = SystemClock.uptimeMillis()
                // 開始握緊時，重置記錄器
                minSqueezeRatioDuringHold = squeezeRatio
            }
        } else {
            // 【關鍵】在持續握緊的過程中，不斷記錄「最小的擠壓比例」(也就是握最緊的瞬間)
            if (squeezeRatio < minSqueezeRatioDuringHold) {
                minSqueezeRatioDuringHold = squeezeRatio
            }

            // 當鬆開大於門檻時，結算這一次的動作
            if (squeezeRatio > RELEASE_THRESHOLD) {
                isSqueezing = false
                val squeezeDuration = SystemClock.uptimeMillis() - squeezeStartTime

                // --- 專業準確率計算 ---
                // 指標 1: 握力深度分數 (Depth Score) - 佔比 60%
                // 算法：(及格門檻 - 實際最緊比例) / (及格門檻 - 完美門檻)
                val depthScore = ((SQUEEZE_THRESHOLD - minSqueezeRatioDuringHold) /
                        (SQUEEZE_THRESHOLD - PERFECT_SQUEEZE_RATIO)).coerceIn(0f, 1f)

                // 指標 2: 停留時間分數 (Hold Score) - 佔比 40%
                // 算法：實際停留時間 / 目標時間 (2秒)
                val holdScore = (squeezeDuration.toFloat() / TARGET_HOLD_MS).coerceIn(0f, 1f)

                // 計算單次總分 (滿分 100)
                val currentAccuracy = (depthScore * 0.9f + holdScore * 0.1f) * 100f

                totalAccuracyAccumulated += currentAccuracy
                accuracyTicks++

                currentRep++
                if (currentRep >= REPS_PER_SET) {
                    if (currentSet < SETS_PER_HAND) {
                        startRestTimer()
                    } else {
                        if (isRightHand) {
                            switchToLeftHand()
                        } else {
                            completeTest()
                        }
                    }
                }
            }
        }

        val status = when {
            isSqueezing -> "請慢慢鬆開手"
            else -> "請慢慢用力緊握"
        }

        val handLabel = if (isRightHand) "右手" else "左手"
        binding.overlay.updateTestInfo(currentRep, currentSet, "$handLabel - $status", calculateAvgAccuracy(), isTestCompleted, "次數", SETS_PER_HAND)
    }

    private fun dist(a: com.google.mediapipe.tasks.components.containers.NormalizedLandmark, b: com.google.mediapipe.tasks.components.containers.NormalizedLandmark): Float {
        return sqrt((a.x() - b.x()).pow(2) + (a.y() - b.y()).pow(2))
    }

    private fun calculateAvgAccuracy() = if (accuracyTicks == 0) 0f else (totalAccuracyAccumulated / accuracyTicks).coerceIn(0f, 100f)

    private fun startRestTimer() {
        isResting = true
        timer?.cancel()
        timer = object : CountDownTimer(SET_REST_TIME_MS, 1000) {
            override fun onTick(ms: Long) {
                currentTimerStatusText = "組間休息 (${ms/1000}s)"
                binding.overlay.updateTestInfo(currentRep, currentSet, currentTimerStatusText, calculateAvgAccuracy(), isTestCompleted, "次數", SETS_PER_HAND)
            }
            override fun onFinish() {
                isResting = false
                currentSet++
                currentRep = 0
            }
        }.start()
    }

    private fun switchToLeftHand() {
        isResting = true
        timer?.cancel()
        timer = object : CountDownTimer(SET_REST_TIME_MS, 1000) {
            override fun onTick(ms: Long) {
                currentTimerStatusText = "換手休息中 (${ms/1000}s)"
                binding.overlay.updateTestInfo(currentRep, currentSet, "即將換左手: $currentTimerStatusText", calculateAvgAccuracy(), isTestCompleted, "次數", SETS_PER_HAND)
            }
            override fun onFinish() {
                isResting = false
                isRightHand = false
                currentSet = 1
                currentRep = 0
                Toast.makeText(requireContext(), "請換左手開始練習", Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun completeTest() {
        isTestCompleted = true
        val finalAccuracy = calculateAvgAccuracy()
        // --- 封包傳送 ---
        val result = ExerciseResult(
            exerciseName = "擠壓球",
            exerciseId = "A-2,B-3",
            accuracy = finalAccuracy,
        )
        viewModel.postResult(result)
        binding.overlay.updateTestInfo(currentRep, currentSet, "全部測試完成！", finalAccuracy, true, "次數", SETS_PER_HAND)
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
                if (this::objectDetectorHelper.isInitialized && objectDetectorHelper.isClosed()) {
                    objectDetectorHelper.setupObjectDetector()
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
        if (this::objectDetectorHelper.isInitialized) {
            backgroundExecutor.execute { objectDetectorHelper.clearObjectDetector() }
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
        backgroundExecutor.shutdown()
    }
}