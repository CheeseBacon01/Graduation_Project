package com.google.mediapipe.examples.poselandmarker.fragment

import android.app.AlertDialog
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.mediapipe.examples.poselandmarker.PoseLandmarkerHelper
import com.google.mediapipe.examples.poselandmarker.databinding.FragmentGaitSpeed4mBinding
import com.google.mediapipe.tasks.vision.core.RunningMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import android.speech.tts.TextToSpeech

enum class AutoTestState4m {
    INPUT_HEIGHT,
    WAITING_FOR_DISTANCE,
    COUNTDOWN,
    TESTING,
    FINISHED
}

class GaitSpeed4mFragment : Fragment(), PoseLandmarkerHelper.LandmarkerListener {

    private var _binding: FragmentGaitSpeed4mBinding? = null
    private val binding get() = _binding!!
    private lateinit var poseLandmarkerHelper: PoseLandmarkerHelper
    private lateinit var backgroundExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraFacing = CameraSelector.LENS_FACING_BACK

    private lateinit var tts: TextToSpeech
    private var isTtsReady = false
    private var lastSpeakTime = 0L
    private var lastSpeechText = ""

    private var testCount = 0
    private var startTime = 0L
    private var bestTime = Float.MAX_VALUE
    private var bestScore = 0
    private var currentSeconds = 0f

    private var currentState = AutoTestState4m.INPUT_HEIGHT

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentGaitSpeed4mBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        backgroundExecutor = Executors.newSingleThreadExecutor()
        binding.viewFinder.post { setUpCamera() }
        tts = TextToSpeech(requireContext()) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale.CHINESE
                isTtsReady = true
            }
        }
        backgroundExecutor.execute {
            poseLandmarkerHelper = PoseLandmarkerHelper(
                context = requireContext(),
                runningMode = RunningMode.LIVE_STREAM,
                poseLandmarkerHelperListener = this
            )
        }

        showHeightDialog()

        binding.btnReset.setOnClickListener {
            resetForNextTrial(true)
        }

        binding.btnUnable.setOnClickListener {
            showResult(0f, 0)
        }

        binding.btnDialogNext.setOnClickListener {
            binding.dialogLayout.visibility = View.GONE
            if (testCount >= 3 || binding.btnDialogNext.text == "結束") {
                findNavController().navigateUp()
            } else {
                resetForNextTrial(false)
            }
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
        if (isTtsReady && (text != lastSpeechText || (currentTime - lastSpeakTime > throttleMs))) {
            if (::tts.isInitialized) {
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
            }
            lastSpeakTime = currentTime
            lastSpeechText = text
        }
    }

    private fun showHeightDialog() {
        currentState = AutoTestState4m.INPUT_HEIGHT
        val input = EditText(requireContext())
        input.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        input.hint = "例如: 165"

        AlertDialog.Builder(requireContext())
            .setTitle("準備開始")
            .setMessage("請輸入受測者的身高 (公分)：")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("確定") { _, _ ->
                val heightStr = input.text.toString()
                binding.overlay.userHeightCm = heightStr.toFloatOrNull() ?: 160f

                currentState = AutoTestState4m.WAITING_FOR_DISTANCE
                binding.tvCenterStatus.text = "請退後至\n大於 4 公尺處"
                speakOut("請退後至大於四公尺處")
                binding.tvCenterStatus.textSize = 50f
            }
            .show()
    }

    private fun setUpCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider ?: return
        val cameraSelector = CameraSelector.Builder().requireLensFacing(cameraFacing).build()
        val preview = Preview.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3).setTargetRotation(binding.viewFinder.display.rotation).build()
        val imageAnalyzer = ImageAnalysis.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3).setTargetRotation(binding.viewFinder.display.rotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888).build()
            .also { it.setAnalyzer(backgroundExecutor) { image -> processImageProxy(image) } }
        cameraProvider.unbindAll()
        cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
        preview.setSurfaceProvider(binding.viewFinder.surfaceProvider)
    }

    private fun processImageProxy(imageProxy: ImageProxy) {
        if (!::poseLandmarkerHelper.isInitialized) { imageProxy.close(); return }
        poseLandmarkerHelper.detectLiveStream(imageProxy, cameraFacing == CameraSelector.LENS_FACING_FRONT)
    }

    override fun onResults(resultBundle: PoseLandmarkerHelper.ResultBundle) {
        activity?.runOnUiThread {
            if (_binding != null) {
                val results = resultBundle.results.firstOrNull() ?: return@runOnUiThread
                val currentDistance = binding.overlay.currentDistance

                when (currentState) {
                    AutoTestState4m.WAITING_FOR_DISTANCE -> {
                        speakOut("請繼續退後", 5000L)
                        if (currentDistance >= 5.0f) {
                            currentState = AutoTestState4m.COUNTDOWN
                            startCountdownSequence()
                        }
                    }
                    AutoTestState4m.TESTING -> {
                        currentSeconds = (SystemClock.elapsedRealtime() - startTime) / 1000f
                        if (currentDistance <= binding.overlay.targetDistance && currentDistance > 0) {
                            finishTrial()
                        }
                    }
                    else -> {}
                }

                binding.overlay.setPoseResults(results, resultBundle.inputImageHeight, resultBundle.inputImageWidth, RunningMode.LIVE_STREAM)

                val msg = when (currentState) {
                    AutoTestState4m.WAITING_FOR_DISTANCE -> "請繼續退後..."
                    AutoTestState4m.TESTING -> "往前走向手機"
                    else -> "準備中"
                }

                binding.overlay.updateTestInfo(
                    count = testCount + 1,
                    sets = if (testCount > 0) testCount + 1 else 1,
                    message = msg,
                    accuracy = 0f,
                    label = "測試次數",
                    maxSets = 3,
                    setLabel = "測試次數",
                    time = String.format(Locale.US, "%.2f", currentSeconds),
                    showAccuracy = false // <-- 加入這行隱藏準確率
                )
            }
        }
    }

    private fun startCountdownSequence() {
        lifecycleScope.launch(Dispatchers.Main) {
            binding.tvCenterStatus.textSize = 100f
            binding.tvCenterStatus.text = "3"
            speakOut("三")
            delay(1000)
            binding.tvCenterStatus.text = "2"
            speakOut("二")
            delay(1000)
            binding.tvCenterStatus.text = "1"
            speakOut("一")
            delay(1000)
            binding.tvCenterStatus.text = "GO!"
            speakOut("開始")

            val startDist = binding.overlay.currentDistance
            binding.overlay.targetDistance = max(0.2f, startDist - 4.0f)
            binding.overlay.isTestingGait = true

            startTime = SystemClock.elapsedRealtime()
            currentState = AutoTestState4m.TESTING

            delay(1000)
            binding.tvCenterStatus.text = ""
        }
    }

    private fun finishTrial() {
        currentState = AutoTestState4m.FINISHED
        binding.overlay.isTestingGait = false
        testCount++

        val duration = (SystemClock.elapsedRealtime() - startTime) / 1000f
        if (duration < bestTime) bestTime = duration

        val score = when {
            bestTime < 4.82f -> 4
            bestTime <= 6.2f -> 3
            bestTime <= 8.7f -> 2
            else -> 1
        }
        if (score > bestScore) bestScore = score
        speakOut("成功")
        showResult(duration, score)
    }

    private fun showResult(duration: Float, score: Int) {
        binding.dialogLayout.visibility = View.VISIBLE
        binding.tvResultMsg.text = String.format(Locale.US, "本次時間: %.2fs\n最短時間: %.2fs\n目前最高分: %d分", duration, bestTime, bestScore)
        val isFinished = testCount >= 3 || score == 0
        binding.btnDialogNext.text = if (!isFinished) "進行第${testCount + 1}次測試" else "結束"
        if (score == 0) {
            binding.tvResultMsg.text = "獲得 0 分"
            speakOut("測試結束")
        }else if (testCount >= 3){
            speakOut("測驗完成")
        }
    }

    private fun resetForNextTrial(askHeight: Boolean) {
        binding.tvCenterStatus.text = ""
        binding.overlay.isTestingGait = false
        currentSeconds = 0f

        if (askHeight) {
            testCount = 0
            bestTime = Float.MAX_VALUE
            bestScore = 0
            showHeightDialog()
        } else {
            currentState = AutoTestState4m.WAITING_FOR_DISTANCE
            binding.tvCenterStatus.text = "請退後至\n大於 4 公尺處"
            binding.tvCenterStatus.textSize = 50f
        }
    }

    override fun onError(error: String, errorCode: Int) { Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show() }
    override fun onDestroyView() {
        isTtsReady = false
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
        _binding = null
        super.onDestroyView()
        backgroundExecutor.shutdown()
    }
}