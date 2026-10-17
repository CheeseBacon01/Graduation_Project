package com.google.mediapipe.examples.poselandmarker.fragment

import android.os.Bundle
import android.os.CountDownTimer
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.mediapipe.examples.poselandmarker.PoseLandmarkerHelper
import com.google.mediapipe.examples.poselandmarker.databinding.FragmentFiveTimesChairStandBinding
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class FiveTimesChairStandFragment : Fragment(), PoseLandmarkerHelper.LandmarkerListener {
    private lateinit var tts: TextToSpeech
    private var isTtsReady = false
    private var lastSpeakTime = 0L
    private var lastSpeechText = ""

    private var _binding: FragmentFiveTimesChairStandBinding? = null
    private val binding get() = _binding!!
    private lateinit var poseLandmarkerHelper: PoseLandmarkerHelper
    private lateinit var backgroundExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraFacing = CameraSelector.LENS_FACING_BACK

    private var count = 0
    private var isSeated = true
    private var startTime = 0L
    private var isTesting = false
    private var isPreparing = false
    private var isCountingDown = false
    private var prepTimer: CountDownTimer? = null
    private var currentCountdownSeconds = 3
    private var currentSeconds = 0f

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentFiveTimesChairStandBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        tts = TextToSpeech(requireContext()) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale.TAIWAN
                isTtsReady = true
            }
        }
        backgroundExecutor = Executors.newSingleThreadExecutor()
        binding.viewFinder.post { setUpCamera() }

        backgroundExecutor.execute {
            poseLandmarkerHelper = PoseLandmarkerHelper(
                context = requireContext(),
                runningMode = RunningMode.LIVE_STREAM,
                poseLandmarkerHelperListener = this
            )
        }

        binding.btnStart.setOnClickListener {
            if (!isTesting && !isPreparing) {
                isPreparing = true
                binding.btnStart.visibility = View.GONE
                speakOut("請坐下準備")
            }
        }

        binding.btnDialogOk.setOnClickListener {
            findNavController().navigateUp()
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

        val preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(binding.viewFinder.display.rotation)
            .build()

        val imageAnalyzer = ImageAnalysis.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(binding.viewFinder.display.rotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
            .also { it.setAnalyzer(backgroundExecutor) { image -> processImageProxy(image) } }

        cameraProvider.unbindAll()
        cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
        preview.setSurfaceProvider(binding.viewFinder.surfaceProvider)
    }

    private fun processImageProxy(imageProxy: ImageProxy) {
        if (!::poseLandmarkerHelper.isInitialized) {
            imageProxy.close()
            return
        }

        val isFrontCamera = cameraFacing == CameraSelector.LENS_FACING_FRONT
        poseLandmarkerHelper.detectLiveStream(imageProxy, isFrontCamera)
    }

    override fun onResults(resultBundle: PoseLandmarkerHelper.ResultBundle) {
        activity?.runOnUiThread {
            if (_binding != null) {
                val results = resultBundle.results.firstOrNull() ?: return@runOnUiThread
                val landmarks = results.landmarks().firstOrNull()

                // 檢查使用者是否處於坐下姿勢
                val userIsSeated = if (landmarks != null && landmarks.size > 26) {
                    val hipY = (landmarks[23].y() + landmarks[24].y()) / 2
                    val kneeY = (landmarks[25].y() + landmarks[26].y()) / 2
                    hipY > kneeY - 0.02f
                } else false

                var statusMsg = "準備中"

                if (isPreparing) {
                    if (userIsSeated) {
                        if (!isCountingDown) {
                            isCountingDown = true
                            startPrepCountdown()
                        }
                        statusMsg = "倒數 $currentCountdownSeconds 秒"
                    } else {
                        if (isCountingDown) {
                            cancelPrepCountdown()
                        }
                        statusMsg = "請坐下"
                        speakOut("請坐下", 2000L)
                    }
                } else if (isTesting) {
                    detectSquat(results)
                    currentSeconds = (SystemClock.elapsedRealtime() - startTime) / 1000f
                    statusMsg = "測試中"
                }

                binding.overlay.setPoseResults(results, resultBundle.inputImageHeight, resultBundle.inputImageWidth, RunningMode.LIVE_STREAM)
                binding.tvCount.text = when {
                    isPreparing && !isCountingDown -> "請就坐"
                    isPreparing && isCountingDown -> "倒數: $currentCountdownSeconds 秒"
                    else -> "次數: $count / 5"
                }

                // 更新 OverlayView 的左上方資訊
                binding.overlay.updateTestInfo(
                    count = count,
                    sets = count,
                    message = statusMsg,
                    accuracy = 0f,
                    label = "起身次數",
                    maxSets = 5,
                    setLabel = "起身次數",
                    time = String.format(Locale.US, "%.2f", currentSeconds),
                    showAccuracy = false
                )
            }
        }
    }

    private fun startPrepCountdown() {
        prepTimer?.cancel()
        currentCountdownSeconds = 3
        speakOut("3")
        prepTimer = object : CountDownTimer(3000L, 200L) {
            override fun onTick(millisUntilFinished: Long) {
                val secondsLeft = ((millisUntilFinished + 999) / 1000).toInt()
                if (secondsLeft != currentCountdownSeconds && secondsLeft in 1..3) {
                    currentCountdownSeconds = secondsLeft
                    speakOut("$secondsLeft")
                }
            }

            override fun onFinish() {
                isPreparing = false
                isCountingDown = false
                isTesting = true
                startTime = SystemClock.elapsedRealtime()
                count = 0
                isSeated = true
                speakOut("開始測試")
            }
        }.start()
    }

    private fun cancelPrepCountdown() {
        prepTimer?.cancel()
        prepTimer = null
        isCountingDown = false
        currentCountdownSeconds = 3
    }

    private fun detectSquat(results: PoseLandmarkerResult) {
        val landmarks = results.landmarks().firstOrNull() ?: return
        if (landmarks.size <= 26) return
        // 11, 12 肩膀; 23, 24 臀部; 25, 26 膝蓋
        val hipY = (landmarks[23].y() + landmarks[24].y()) / 2
        val kneeY = (landmarks[25].y() + landmarks[26].y()) / 2

        if (isSeated && hipY < kneeY - 0.05f) {
            isSeated = false // 站起
            speakOut("坐下")
        } else if (!isSeated && hipY > kneeY - 0.02f) {
            isSeated = true // 坐下
            speakOut("起立")
            count++
            if (count >= 5) finishTest()
        }
    }

    private fun finishTest() {
        isTesting = false
        cancelPrepCountdown()
        speakOut("測驗完成")
        val duration = (SystemClock.elapsedRealtime() - startTime) / 1000f
        val score = when {
            duration <= 11.19f -> 4
            duration <= 13.69f -> 3
            duration <= 16.69f -> 2
            duration <= 59f -> 1
            else -> 0
        }
        binding.dialogLayout.visibility = View.VISIBLE
        binding.tvDialogResult.text = String.format(Locale.US, "完成時間: %.2fs\n獲得 %d 分", duration, score)
    }

    override fun onError(error: String, errorCode: Int) {
        Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        cancelPrepCountdown()
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
            isTtsReady = false
        }
        _binding = null
        super.onDestroyView()
        backgroundExecutor.shutdown()
    }
}