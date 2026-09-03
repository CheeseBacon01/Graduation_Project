package com.google.mediapipe.examples.poselandmarker.fragment

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
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
import com.google.mediapipe.examples.poselandmarker.databinding.FragmentWalkingBinding
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import com.google.mediapipe.examples.poselandmarker.ExerciseResult

class WalkingDFragment : Fragment(), PoseLandmarkerHelper.LandmarkerListener {

    enum class State {
        SITTING, STANDING_UP, WALKING, RESTING, COOL_DOWN
    }

    companion object {
        private const val TAG = "WalkingDFragment"
        private const val VISIBILITY_THRESHOLD = 0.5f
        private const val TARGET_STEPS_PER_SEC = 1.0f 
        private const val MIN_STEP_DISTANCE = 0.02f
        private const val MAX_SETS = 2 
        private const val WALK_TIME_SEC = 20 * 60 
        private const val REST_TIME_MS = 60000L 
        private const val COOL_DOWN_TIME_MS = 120000L 
    }

    private var _binding: FragmentWalkingBinding? = null
    private val binding get() = _binding!!

    private lateinit var poseLandmarkerHelper: PoseLandmarkerHelper
    private val viewModel: MainViewModel by activityViewModels()
    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraFacing = CameraSelector.LENS_FACING_FRONT

    private var currentState = State.SITTING
    private var currentSessionWalkTimeMs = 0L
    private var restTimeLeftMs = REST_TIME_MS
    private var coolDownTimeLeftMs = COOL_DOWN_TIME_MS
    private var lastFrameTime = 0L
    private var isTestCompleted = false
    private var isTrainingStarted = false

    private var currentSet = 1
    private var totalStepCount = 0
    private var lastLeadingFoot = -1
    private var lastKneeY = 0f

    private lateinit var backgroundExecutor: ExecutorService

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentWalkingBinding.inflate(inflater, container, false)
        return binding.root
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        backgroundExecutor = Executors.newSingleThreadExecutor()

        setupUI()

        binding.btnStartTraining.setOnClickListener {
            binding.overlay.updateTestInfo(0, 1, "請坐在椅子上準備", 0f, false, "總步數", MAX_SETS)
            binding.setupPanel.visibility = View.GONE
            isTrainingStarted = true
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

        binding.btnRestDone.setOnClickListener {
            startNextSet()
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

    private fun setupUI() {
        binding.spinnerWalkTime.visibility = View.GONE
        binding.spinnerTotalTime.visibility = View.GONE

        val parent = binding.setupPanel.getChildAt(0) as ViewGroup
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (child is TextView) {
                when (child.text.toString()) {
                    "步行訓練設定" -> child.text = "步行訓練"
                    "單次起身行走時間 (5-10秒):" -> child.text = "目標：每組 20 分鐘，共 2 組"
                    "累計總行走時間 (60-120秒):" -> child.visibility = View.GONE
                }
            }
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
        activity?.runOnUiThread {
            if (_binding != null && isTrainingStarted) {
                val results = resultBundle.results.first()
                processWalkingLogic(results)
                binding.overlay.setPoseResults(
                    results, resultBundle.inputImageHeight, resultBundle.inputImageWidth, RunningMode.LIVE_STREAM
                )
            }
        }
    }

    private fun processWalkingLogic(results: PoseLandmarkerResult) {
        val currentTime = System.currentTimeMillis()
        if (lastFrameTime == 0L) lastFrameTime = currentTime
        val deltaTime = currentTime - lastFrameTime
        lastFrameTime = currentTime

        if (isTestCompleted) return

        if (currentState == State.RESTING) {
            restTimeLeftMs -= deltaTime
            if (restTimeLeftMs <= 0) {
                startNextSet()
            } else {
                binding.overlay.updateTestInfo(totalStepCount, currentSet, "休息時間 (剩餘 ${restTimeLeftMs/1000}s)", calculateAccuracy(), false, "總步數", MAX_SETS, time = (restTimeLeftMs/1000).toString())
            }
            return
        }

        if (currentState == State.COOL_DOWN) {
            coolDownTimeLeftMs -= deltaTime
            if (coolDownTimeLeftMs <= 0) {
                completeTest()
            } else {
                binding.overlay.updateTestInfo(totalStepCount, currentSet, "全部完成！慢走冷卻中 (${coolDownTimeLeftMs/1000}s)", calculateAccuracy(), false, "總步數", MAX_SETS, time = (coolDownTimeLeftMs/1000).toString(), showAccuracy = false)
            }
            return
        }

        val landmarks = if (results.landmarks().isNotEmpty()) results.landmarks()[0] else null
        if (landmarks == null) {
            binding.overlay.updateTestInfo(totalStepCount, currentSet, "請將全身放入畫面", calculateAccuracy(), isTestCompleted, "總步數", MAX_SETS)
            return
        }

        val isVisible = intArrayOf(11, 12, 23, 24).all { landmarks[it].visibility().orElse(0f) > VISIBILITY_THRESHOLD }
        if (!isVisible) {
            binding.overlay.updateTestInfo(totalStepCount, currentSet, "請將全身放入畫面", calculateAccuracy(), isTestCompleted, "總步數", MAX_SETS)
            return
        }

        val leftHip = landmarks[23]; val rightHip = landmarks[24]
        val leftKnee = landmarks[25]; val rightKnee = landmarks[26]
        val leftAnkle = landmarks[27]; val rightAnkle = landmarks[28]

        val hipKneeDist = (abs(leftHip.y() - leftKnee.y()) + abs(rightHip.y() - rightKnee.y())) / 2f
        val currentKneeY = (leftKnee.y() + rightKnee.y()) / 2f
        val kneeMovement = abs(currentKneeY - lastKneeY)
        lastKneeY = currentKneeY

        val yDiff = leftAnkle.y() - rightAnkle.y()
        val currentLeadingFoot = when {
            yDiff > MIN_STEP_DISTANCE -> 0
            yDiff < -MIN_STEP_DISTANCE -> 1
            else -> lastLeadingFoot
        }

        if (currentState == State.WALKING) {
            if (lastLeadingFoot != -1 && currentLeadingFoot != lastLeadingFoot) {
                totalStepCount++
            }
        }
        lastLeadingFoot = currentLeadingFoot

        when (currentState) {
            State.SITTING -> {
                if (hipKneeDist > 0.12f) currentState = State.STANDING_UP
            }
            State.STANDING_UP -> {
                if (kneeMovement > 0.005f) currentState = State.WALKING
            }
            State.WALKING -> {
                currentSessionWalkTimeMs += deltaTime
                if (currentSessionWalkTimeMs >= WALK_TIME_SEC * 1000L) {
                    if (currentSet < MAX_SETS) {
                        pauseForRest()
                    } else {
                        startCoolDown()
                    }
                }
            }
            else -> {}
        }

        val statusText = when (currentState) {
            State.SITTING -> "第 $currentSet 組：請坐在椅子上準備"
            State.STANDING_UP -> "第 $currentSet 組：起身中..."
            State.WALKING -> "步行中 (本組剩餘: ${WALK_TIME_SEC - currentSessionWalkTimeMs/1000}s)"
            else -> ""
        }

        if (statusText.isNotEmpty()) {
            binding.overlay.updateTestInfo(totalStepCount, currentSet, statusText, calculateAccuracy(), isTestCompleted, "總步數", MAX_SETS)
        }
    }

    private fun pauseForRest() {
        currentState = State.RESTING
        restTimeLeftMs = REST_TIME_MS
        activity?.runOnUiThread {
            binding.btnRestDone.visibility = View.VISIBLE
            binding.btnRestDone.text = "跳過休息，開始下一組"
        }
    }

    private fun startNextSet() {
        activity?.runOnUiThread {
            binding.btnRestDone.visibility = View.GONE
        }
        currentSessionWalkTimeMs = 0L
        currentSet++
        currentState = State.STANDING_UP
        lastLeadingFoot = -1
        lastFrameTime = System.currentTimeMillis()
    }

    private fun startCoolDown() {
        currentState = State.COOL_DOWN
        coolDownTimeLeftMs = COOL_DOWN_TIME_MS
        activity?.runOnUiThread {
            binding.btnRestDone.visibility = View.GONE
        }
    }

    private fun calculateAccuracy(): Float {
        val completedSetsTime = (currentSet - 1) * WALK_TIME_SEC
        val currentSetTime = currentSessionWalkTimeMs / 1000f
        val totalSec = completedSetsTime + currentSetTime
        if (totalSec <= 0) return 0f
        val targetSteps = totalSec * TARGET_STEPS_PER_SEC
        return (totalStepCount.toFloat() / targetSteps * 100f).coerceIn(0f, 100f)
    }

    private fun completeTest() {
        isTestCompleted = true
        val finalAccuracy = calculateAccuracy()
        val totalSeconds = MAX_SETS * WALK_TIME_SEC

        // --- 封包傳送 ---
        val result = ExerciseResult(
            exerciseName = "步行訓練(D級)",
            exerciseId = "D-1",
            steps = totalStepCount,
            sets = MAX_SETS,
            accuracy = finalAccuracy,
            durationSeconds = totalSeconds
        )
        viewModel.postResult(result)
        binding.overlay.updateTestInfo(totalStepCount, currentSet, "訓練完成！", finalAccuracy, true, "總步數", MAX_SETS)
        binding.resultPanel.visibility = View.VISIBLE
        binding.btnRestDone.visibility = View.GONE
        val totalWalkingTime = MAX_SETS * WALK_TIME_SEC
        binding.tvFinalResult.text = String.format(Locale.US, "級別: 步行(D級)\n總平均準確率: %.1f%%\n累計步數: %d 步\n訓練時間: %d 分鐘", finalAccuracy, totalStepCount, totalWalkingTime / 60)
    }

    override fun onPause() {
        super.onPause()
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
