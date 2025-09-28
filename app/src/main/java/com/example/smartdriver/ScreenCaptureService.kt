package com.example.smartdriver

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.*
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.example.smartdriver.overlay.OverlayService
import com.example.smartdriver.utils.ImageAnalysisUtils
import com.example.smartdriver.utils.OfferData
import com.example.smartdriver.utils.OcrTextRecognizer
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max

class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "screen_capture_channel"
        private const val CHANNEL_NAME = "Screen Capture Service"

        const val ACTION_STOP_CAPTURE = "com.example.smartdriver.screen_capture.STOP"
        const val ACTION_CAPTURE_NOW = "com.example.smartdriver.screen_capture.CAPTURE_NOW"
        const val ACTION_UPDATE_SETTINGS = "com.example.smartdriver.screen_capture.UPDATE_SETTINGS"
        const val ACTION_SAVE_LAST_VALID_OFFER_SCREENSHOT = "com.example.smartdriver.screen_capture.SAVE_LAST_VALID"

        // Congela OCR por curto período (usado na visualização de screenshots do histórico)
        const val ACTION_FREEZE_OCR = "com.example.smartdriver.screen_capture.FREEZE_OCR"
        // Retoma OCR após fechar a visualização
        const val ACTION_RESUME_AFTER_PREVIEW = "com.example.smartdriver.screen_capture.RESUME_AFTER_PREVIEW"

        const val ACTION_OCR_ENABLE = "com.example.smartdriver.screen_capture.OCR_ENABLE"
        const val ACTION_OCR_DISABLE = "com.example.smartdriver.screen_capture.OCR_DISABLE"
        const val ACTION_OCR_REINIT = "com.example.smartdriver.screen_capture.OCR_REINIT"

        // Mantida só para compat (não usada para pedir permissão):
        const val ACTION_NEED_PROJECTION_CONSENT = "com.example.smartdriver.screen_capture.NEED_CONSENT"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val KEY_SAVE_IMAGES = "save_images"

        @JvmStatic val isRunning = AtomicBoolean(false)

        private const val OCR_IMAGE_SCALE_FACTOR = 1.0f
        private const val OFFER_PROCESSING_LOCK_PERIOD_MS = 3000L
        private const val HASH_CACHE_DURATION_MS = 800L
        private const val KEY_OCR_DESIRED = "ocr_desired_on"

        /** Hard-off da persistência de screenshots (histórico sem imagens). */
        private const val DISABLE_SCREENSHOT_PERSISTENCE = true
    }

    // Projeção / Captura
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    // Estado/Threads
    private val isCapturingActive = AtomicBoolean(false)
    private val isProcessingImage = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var processingThread: HandlerThread
    private lateinit var processingHandler: Handler

    @Volatile private var ocrEnabled: Boolean = true
    private lateinit var preferences: SharedPreferences
    @Volatile private var userStopRequested: Boolean = false

    // OCR / análise
    private val imageAnalysisUtils = ImageAnalysisUtils()
    private val ocr: OcrTextRecognizer = OcrTextRecognizer.getInstance()

    // Duplicação/recência
    @Volatile private var lastOfferDetectedTime = 0L
    private var lastDetectedOfferSignature: String? = null

    // Hash cache
    private var lastScreenHash: Int? = null
    private var lastHashTime: Long = 0L

    // Screenshot pendente (buffer estável) — **não será mais persistido**
    @Volatile private var lastBitmapForPotentialSave: Bitmap? = null
    private var screenshotCounter = 0

    // Freeze curto
    @Volatile private var ocrFreezeUntil = 0L

    // Buffer CANDIDATO (instantâneo da primeira deteção) — mantemos para não alterar o fluxo
    @Volatile private var lastCandidateBitmap: Bitmap? = null
    @Volatile private var lastCandidateSignature: String? = null
    @Volatile private var lastCandidateTime: Long = 0L

    // Meta buffers / dedupe
    @Volatile private var bufferedOfferSignature: String? = null
    @Volatile private var lastSavedOfferSignature: String? = null
    @Volatile private var lastSavedTime: Long = 0L

    // NOVO: oferta em espera para a viagem seguinte (desativado para persistência)
    @Volatile private var nextOfferBitmap: Bitmap? = null
    @Volatile private var nextOfferSignature: String? = null

    // Coordenação com Overlay (latch do “A recolher”)
    @Volatile private var inFlightPickupLatch: Boolean = false
    @Volatile private var currentTripId: String? = null

    // STEP 3 — Debounce do popup "Acompanhar viagem"
    @Volatile private var lastStartPromptMs: Long = 0L
    private val START_PROMPT_DEBOUNCE_MS = 3500L

    private fun isOcrAllowedNow(): Boolean = System.currentTimeMillis() >= ocrFreezeUntil
    fun freezeOcrFor(ms: Long = 900L) { ocrFreezeUntil = System.currentTimeMillis() + ms }

    // Estabilidade multi-frame
    private class StabilityWindow(
        private val windowMs: Long = 1200L,
        private val minVotes: Int = 3
    ) {
        private data class Item(val sig: String, val t: Long, val data: OfferData)
        private val items = ArrayDeque<Item>()
        fun add(candidate: OfferData): OfferData? {
            val now = System.currentTimeMillis()
            val sig = candidateSignature(candidate)
            items.addLast(Item(sig, now, candidate))
            while (items.isNotEmpty() && now - items.first.t > windowMs) items.removeFirst()
            val counts = mutableMapOf<String, Int>()
            for (it in items) counts[it.sig] = (counts[it.sig] ?: 0) + 1
            val bestSig = counts.maxByOrNull { it.value }?.key ?: return null
            val votes = counts[bestSig] ?: 0
            return if (votes >= minVotes) items.lastOrNull { it.sig == bestSig }?.data else null
        }
        private fun candidateSignature(o: OfferData): String {
            fun n(s: String?) = (s ?: "").replace(",", ".").trim()
            return "v:${n(o.value)}|pd:${n(o.pickupDistance)}|td:${n(o.tripDistance)}|pt:${n(o.pickupDuration)}|tt:${n(o.tripDuration)}"
        }
        fun clear() = items.clear()
    }
    private val stability = StabilityWindow(windowMs = 1200L, minVotes = 3)

    // Regras
    private data class BizRules(
        val minEurPerKm: Double = 0.38,
        val minMinutes: Int = 7,
        val lowFareThreshold: Double = 3.50,
        val shortTotalKm: Double = 8.0,
        val maxPickupRatio: Double = 0.80,
        val maxAbsurdPrice: Double = 100.0
    )
    private val rules = BizRules()
    private enum class Verdict { VALID, SUSPECT, INVALID }

    private fun classify(stable: OfferData): Verdict {
        fun d(s: String?) = s?.replace(",", ".")?.toDoubleOrNull()
        fun i(s: String?) = s?.toIntOrNull()

        val price = d(stable.value) ?: return Verdict.INVALID
        if (price <= 0.2) return Verdict.INVALID
        if (price > rules.maxAbsurdPrice) return Verdict.INVALID

        val pd = d(stable.pickupDistance) ?: 0.0
        val td = d(stable.tripDistance) ?: 0.0
        val pt = i(stable.pickupDuration) ?: 0
        val tt = i(stable.tripDuration) ?: 0

        val totalKm = (pd + td).takeIf { it > 0 } ?: td
        val totalMin = (pt + tt).takeIf { it > 0 } ?: tt

        val isLowShort = price <= rules.lowFareThreshold && totalKm <= rules.shortTotalKm

        val eurKm = if (totalKm > 0.05) price / totalKm else Double.POSITIVE_INFINITY
        val priceOk = isLowShort || eurKm >= rules.minEurPerKm
        val timeOk  = isLowShort || totalMin >= rules.minMinutes
        val pickupOk = if (totalKm <= 0.1) true else (pd <= rules.maxPickupRatio * totalKm)

        val nearPrice = !priceOk && eurKm >= (rules.minEurPerKm * 0.9)
        val nearTime  = !timeOk  && totalMin >= (rules.minMinutes - 1)
        val nearPickup = !pickupOk && totalKm > 0.1 && pd <= (rules.maxPickupRatio * 1.1) * totalKm

        return when {
            priceOk && timeOk && pickupOk -> Verdict.VALID
            nearPrice || nearTime || nearPickup -> Verdict.SUSPECT
            else -> Verdict.INVALID
        }
    }

    private val overlayEventsReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            when (i?.action) {
                OverlayService.ACTION_EVT_TRACKING_STARTED -> {
                    inFlightPickupLatch = true
                    currentTripId = try { i.getStringExtra(OverlayService.EXTRA_TRIP_ID) } catch (_: Exception) { null }
                    Log.i(TAG, "EVENT: tracking started → latch ON (tripId=$currentTripId)")

                    // **Persistência de screenshot desativada**
                    processingHandler.post {
                        recyclePotentialSaveBitmap("persistência desativada (tracking started)")
                        try { if (lastCandidateBitmap != null && !lastCandidateBitmap!!.isRecycled) lastCandidateBitmap!!.recycle() } catch (_: Exception) {}
                        lastCandidateBitmap = null
                    }
                }
                OverlayService.ACTION_EVT_TRACKING_ENDED -> {
                    inFlightPickupLatch = false
                    currentTripId = null
                    stability.clear()
                    lastDetectedOfferSignature = null
                    Log.i(TAG, "EVENT: tracking ended → latch OFF")

                    // **Sem promover oferta em espera; limpa e recicla buffers**
                    try { nextOfferBitmap?.recycle() } catch (_: Exception) {}
                    nextOfferBitmap = null; nextOfferSignature = null
                    recyclePotentialSaveBitmap("tracking ended")
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning.set(true)

        preferences = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        ocrEnabled = preferences.getBoolean(KEY_OCR_DESIRED, true)
        userStopRequested = false

        processingThread = HandlerThread("ImageProcessingThread", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
        processingHandler = Handler(processingThread.looper)

        getScreenMetrics()
        startForeground(NOTIFICATION_ID, createNotification("SmartDriver ativo"))

        // Watchdog
        mainHandler.postDelayed(object : Runnable {
            override fun run() {
                try {
                    if (isRunning.get() && ocrEnabled && !userStopRequested && !isCapturingActive.get()) {
                        if (hasValidProjectionData()) {
                            tryStartCaptureWithoutPrompt("watchdog")
                        }
                    }
                } catch (_: Exception) {}
                finally { mainHandler.postDelayed(this, 5000L) }
            }
        }, 3000L)

        val flt = IntentFilter().apply {
            addAction(OverlayService.ACTION_EVT_TRACKING_STARTED)
            addAction(OverlayService.ACTION_EVT_TRACKING_ENDED)
        }
        try { registerReceiver(overlayEventsReceiver, flt) } catch (_: Exception) {}

        updateNotification(if (ocrEnabled) "SmartDriver pronto" else "SmartDriver (OCR desligado)")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Dados de consentimento opcionais vindos da Activity
        if (intent?.hasExtra(EXTRA_RESULT_CODE) == true && intent.hasExtra(EXTRA_RESULT_DATA)) {
            val code = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            val data = getParcelableExtraCompat(intent, EXTRA_RESULT_DATA, Intent::class.java)?.clone() as? Intent
            if (code == Activity.RESULT_OK && data != null) {
                MediaProjectionData.resultCode = code
                MediaProjectionData.resultData = data
                Log.i(TAG, "Consentimento recebido da Activity (armazenado em MediaProjectionData).")
                if (ocrEnabled && !userStopRequested && !isCapturingActive.get()) {
                    setupMediaProjection()
                    startScreenCaptureInternal()
                }
            } else {
                updateNotification("SmartDriver (autorização negada)")
            }
        }

        when (intent?.action) {
            ACTION_STOP_CAPTURE, ACTION_OCR_DISABLE -> {
                preferences.edit().putBoolean(KEY_OCR_DESIRED, false).apply()
                ocrEnabled = false
                userStopRequested = true
                stopScreenCaptureInternal()
                updateNotification("SmartDriver (captura parada)")
            }

            ACTION_OCR_ENABLE -> {
                preferences.edit().putBoolean(KEY_OCR_DESIRED, true).apply()
                ocrEnabled = true
                userStopRequested = false
                if (hasValidProjectionData()) {
                    tryStartCaptureWithoutPrompt("enable-with-existing-data")
                } else {
                    updateNotification("SmartDriver pronto (aguardar autorização)")
                }
            }

            ACTION_OCR_REINIT -> {
                if (preferences.getBoolean(KEY_OCR_DESIRED, true)) {
                    ocrEnabled = true
                    userStopRequested = false
                    reinitCapturePipeline()
                } else {
                    ocrEnabled = false
                    updateNotification("SmartDriver (OCR desligado)")
                }
            }

            ACTION_CAPTURE_NOW -> {
                if (mediaProjection != null && imageReader != null && isCapturingActive.get()) {
                    processingHandler.post { processAvailableImage(imageReader) }
                }
            }

            ACTION_SAVE_LAST_VALID_OFFER_SCREENSHOT -> {
                // **Desativado**: não guarda mais screenshots
                processingHandler.post { recyclePotentialSaveBitmap("save manual desativado") }
            }

            ACTION_UPDATE_SETTINGS -> {
                // **Ignora pedidos para gravar imagens** e força off
                if (intent.hasExtra(KEY_SAVE_IMAGES)) {
                    preferences.edit().putBoolean(KEY_SAVE_IMAGES, false).apply()
                    processingHandler.post { recyclePotentialSaveBitmap("config forçada OFF") }
                }
            }

            ACTION_FREEZE_OCR -> freezeOcrFor(900L)

            ACTION_RESUME_AFTER_PREVIEW -> {
                ocrFreezeUntil = 0L
                if (!isCapturingActive.get() && hasValidProjectionData() && ocrEnabled && !userStopRequested) {
                    tryStartCaptureWithoutPrompt("resume-after-preview")
                }
            }
        }
        return START_STICKY
    }

    private fun shouldAutoRestart(): Boolean {
        return isRunning.get() && ocrEnabled && !userStopRequested && preferences.getBoolean(KEY_OCR_DESIRED, true)
    }

    private fun hasValidProjectionData(): Boolean {
        return MediaProjectionData.resultCode == Activity.RESULT_OK && MediaProjectionData.resultData != null
    }

    private fun tryStartCaptureWithoutPrompt(reason: String) {
        if (!ocrEnabled || userStopRequested) return
        if (!hasValidProjectionData()) return
        if (mediaProjection == null) setupMediaProjection()
        if (imageReader == null) setupImageReader()
        startScreenCaptureInternal()
        if (isCapturingActive.get()) updateNotification("SmartDriver a monitorizar…")
    }

    private fun reinitCapturePipeline() {
        stopScreenCaptureInternal()
        stability.clear()
        lastScreenHash = null
        lastDetectedOfferSignature = null
        lastOfferDetectedTime = 0L
        inFlightPickupLatch = false
        mainHandler.postDelayed({ tryStartCaptureWithoutPrompt("reinit") }, 250L)
    }

    private fun <T : Any?> getParcelableExtraCompat(intent: Intent?, key: String, clazz: Class<T>): T? {
        return intent?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) it.getParcelableExtra(key, clazz)
            else @Suppress("DEPRECATION") it.getParcelableExtra(key) as? T
        }
    }

    private fun getScreenMetrics() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val m = wm.currentWindowMetrics
            screenWidth = m.bounds.width()
            screenHeight = m.bounds.height()
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
            screenWidth = dm.widthPixels
            screenHeight = dm.heightPixels
        }
        screenDensity = resources.configuration.densityDpi
        if (screenWidth <= 0 || screenHeight <= 0) {
            screenWidth = 1080; screenHeight = 1920; screenDensity = DisplayMetrics.DENSITY_DEFAULT
        }
    }

    private fun setupMediaProjection() {
        if (mediaProjection != null) return
        val code = MediaProjectionData.resultCode
        val data = MediaProjectionData.resultData
        if (code != Activity.RESULT_OK || data == null) return

        val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try {
            mediaProjection = pm.getMediaProjection(code, data)
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    if (userStopRequested) return
                    Log.w(TAG, "MediaProjection onStop() – captura parada externamente.")
                    isCapturingActive.set(false)

                    try { virtualDisplay?.release() } catch (_: Exception) {}
                    virtualDisplay = null
                    try { imageReader?.close() } catch (_: Exception) {}
                    imageReader = null
                    mediaProjection = null

                    if (shouldAutoRestart() && hasValidProjectionData()) {
                        mainHandler.postDelayed({ tryStartCaptureWithoutPrompt("mp.onStop") }, 800L)
                    } else {
                        updateNotification("SmartDriver (aguardar autorização)")
                    }
                }
            }, mainHandler)
            setupImageReader()
        } catch (e: Exception) {
            Log.e(TAG, "Erro MediaProjection: ${e.message}", e)
            mediaProjection = null
        }
    }

    private fun setupImageReader() {
        if (imageReader != null) return
        if (screenWidth <= 0 || screenHeight <= 0) return
        try {
            imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
            imageReader?.setOnImageAvailableListener({ reader -> processAvailableImage(reader) }, processingHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ImageReader: ${e.message}", e)
            imageReader = null
        }
    }

    private fun startScreenCaptureInternal() {
        if (!ocrEnabled || userStopRequested) return
        if (mediaProjection == null) {
            setupMediaProjection()
            if (mediaProjection == null) return
        }
        if (imageReader == null) {
            setupImageReader()
            if (imageReader == null) return
        }
        if (isCapturingActive.get()) return

        try {
            virtualDisplay = mediaProjection!!.createVirtualDisplay(
                "ScreenCapture", screenWidth, screenHeight, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface,
                null,
                processingHandler
            )
            isCapturingActive.set(true)
            updateNotification("SmartDriver a monitorizar…")
        } catch (e: Exception) {
            Log.e(TAG, "Erro VirtualDisplay: ${e.message}", e)
            isCapturingActive.set(false)
        }
    }

    private fun stopScreenCaptureInternal() {
        if (!isCapturingActive.getAndSet(false)) {
            try { virtualDisplay?.release() } catch (_: Exception) {} finally { virtualDisplay = null }
            try { imageReader?.close() } catch (_: Exception) {} finally { imageReader = null }
            try { mediaProjection?.stop() } catch (_: Exception) {} finally { mediaProjection = null }
            return
        }
        try { virtualDisplay?.release() } catch (_: Exception) {} finally { virtualDisplay = null }
        try { imageReader?.close() } catch (_: Exception) {} finally { imageReader = null }
        try { mediaProjection?.stop() } catch (_: Exception) {} finally { mediaProjection = null }
        isProcessingImage.set(false)
        lastScreenHash = null
        if (::processingThread.isInitialized && processingThread.isAlive) {
            processingHandler.post { recyclePotentialSaveBitmap("captura parada") }
        } else {
            recyclePotentialSaveBitmap("captura parada")
        }
        updateNotification("SmartDriver (captura parada)")
    }

    private fun processAvailableImage(reader: ImageReader?) {
        if (reader == null || !isCapturingActive.get() || !ocrEnabled || userStopRequested) return

        val now = System.currentTimeMillis()
        if (now - lastOfferDetectedTime < OFFER_PROCESSING_LOCK_PERIOD_MS / 2) {
            reader.acquireLatestImage()?.close(); return
        }
        if (!isOcrAllowedNow()) { reader.acquireLatestImage()?.close(); return }
        if (!isProcessingImage.compareAndSet(false, true)) {
            reader.acquireLatestImage()?.close(); return
        }

        var image: Image? = null
        var originalBitmap: Bitmap? = null
        try {
            image = reader.acquireLatestImage()
            if (image == null) { isProcessingImage.set(false); return }
            originalBitmap = imageToBitmap(image)
            image.close(); image = null

            if (originalBitmap == null) { isProcessingImage.set(false); return }

            val hash = calculateImageHash(originalBitmap)
            val tHash = System.currentTimeMillis()
            if (tHash - lastHashTime < HASH_CACHE_DURATION_MS && hash == lastScreenHash) {
                originalBitmap.recycle(); originalBitmap = null
                isProcessingImage.set(false); return
            }
            lastScreenHash = hash; lastHashTime = tHash

            val roi = imageAnalysisUtils.getRegionsOfInterest(originalBitmap.width, originalBitmap.height).firstOrNull()
            val bitmapToAnalyze: Bitmap = if (roi != null && !roi.isEmpty && roi.width() > 0 && roi.height() > 0) {
                imageAnalysisUtils.cropToRegion(originalBitmap, roi) ?: originalBitmap
            } else originalBitmap

            if (bitmapToAnalyze !== originalBitmap && !originalBitmap.isRecycled) originalBitmap.recycle()

            processBitmapRegion(bitmapToAnalyze, screenWidth, screenHeight, "ROI")
        } catch (_: Exception) {
            originalBitmap?.recycle(); image?.close(); isProcessingImage.set(false)
        }
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        if (image.format != PixelFormat.RGBA_8888) return null
        val planes = image.planes
        if (planes.isEmpty()) return null
        val buffer = planes[0].buffer ?: return null
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        if (pixelStride <= 0 || rowStride <= 0) return null
        val rowPadding = max(0, rowStride - pixelStride * image.width)
        return try {
            val padWidth = image.width + rowPadding / pixelStride
            if (padWidth <= 0 || image.height <= 0) return null
            val bmpPad = Bitmap.createBitmap(padWidth, image.height, Bitmap.Config.ARGB_8888)
            bmpPad.copyPixelsFromBuffer(buffer)
            if (rowPadding == 0) bmpPad
            else {
                val finalBmp = Bitmap.createBitmap(bmpPad, 0, 0, image.width.coerceAtLeast(1), image.height.coerceAtLeast(1))
                bmpPad.recycle(); finalBmp
            }
        } catch (_: Throwable) { null }
    }

    private fun calculateImageHash(bitmap: Bitmap): Int {
        val scaleSize = 64
        return try {
            val scaledBitmap = Bitmap.createScaledBitmap(bitmap, scaleSize, scaleSize, true)
            val pixels = IntArray(scaleSize * scaleSize)
            scaledBitmap.getPixels(pixels, 0, scaleSize, 0, 0, scaleSize, scaleSize)
            scaledBitmap.recycle()
            pixels.contentHashCode()
        } catch (_: Exception) { bitmap.hashCode() }
    }

    private fun preprocessBitmapForOcr(originalBitmap: Bitmap?): Bitmap? {
        if (originalBitmap == null || originalBitmap.isRecycled) return originalBitmap
        var processedBitmap: Bitmap? = null
        return try {
            processedBitmap = Bitmap.createBitmap(originalBitmap.width, originalBitmap.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(processedBitmap)
            val paint = Paint()
            val contrastValue = 1.5f
            val brightnessValue = -(128 * (contrastValue - 1))
            val colorMatrix = ColorMatrix(floatArrayOf(
                contrastValue, 0f, 0f, 0f, brightnessValue,
                0f, contrastValue, 0f, 0f, brightnessValue,
                0f, 0f, contrastValue, 0f, brightnessValue,
                0f, 0f, 0f, 1f, 0f
            ))
            paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
            canvas.drawBitmap(originalBitmap, 0f, 0f, paint)
            processedBitmap
        } catch (_: OutOfMemoryError) {
            processedBitmap?.recycle(); null
        } catch (_: Exception) {
            processedBitmap?.recycle(); originalBitmap
        }
    }

    private fun processBitmapRegion(bitmapToProcess: Bitmap, originalWidth: Int, originalHeight: Int, regionTag: String) {
        var bitmapAfterResize: Bitmap? = null
        var bitmapPreprocessed: Bitmap? = null
        var bitmapCopyForListeners: Bitmap? = null

        try {
            val scaleFactor = OCR_IMAGE_SCALE_FACTOR
            bitmapAfterResize = if (abs(scaleFactor - 1.0f) > 0.01f) {
                val nw = (bitmapToProcess.width * scaleFactor).toInt()
                val nh = (bitmapToProcess.height * scaleFactor).toInt()
                if (nw > 0 && nh > 0) Bitmap.createScaledBitmap(bitmapToProcess, nw, nh, true) else bitmapToProcess
            } else bitmapToProcess

            if (bitmapAfterResize == null || bitmapAfterResize.isRecycled) {
                if (bitmapToProcess !== bitmapAfterResize && !bitmapToProcess.isRecycled) bitmapToProcess.recycle()
                isProcessingImage.compareAndSet(true, false); return
            }

            bitmapPreprocessed = preprocessBitmapForOcr(bitmapAfterResize)
            if (bitmapPreprocessed == null || bitmapPreprocessed.isRecycled) {
                if (bitmapAfterResize !== bitmapToProcess && !bitmapAfterResize.isRecycled) bitmapAfterResize.recycle()
                if (bitmapToProcess !== bitmapAfterResize && !bitmapToProcess.isRecycled) bitmapToProcess.recycle()
                isProcessingImage.compareAndSet(true, false); return
            }

            try {
                bitmapCopyForListeners = bitmapPreprocessed.copy(bitmapPreprocessed.config ?: Bitmap.Config.ARGB_8888, false)
            } catch (_: Exception) { bitmapCopyForListeners = null }

            if (bitmapPreprocessed !== bitmapAfterResize && !bitmapAfterResize.isRecycled) bitmapAfterResize.recycle()
            if (bitmapPreprocessed !== bitmapToProcess && !bitmapToProcess.isRecycled) bitmapToProcess.recycle()
            if (bitmapCopyForListeners !== bitmapPreprocessed && !bitmapPreprocessed.isRecycled) bitmapPreprocessed.recycle()

            if (bitmapCopyForListeners == null || bitmapCopyForListeners.isRecycled) {
                isProcessingImage.compareAndSet(true, false)
                recyclePotentialSaveBitmap("falha copiar OCR")
                return
            }

            try {
                ocr.processThrottled(bitmapCopyForListeners, 0, 350)
                    .addOnSuccessListener listener@{ visionText ->
                        val extractedText = visionText.text
                        if (extractedText.isBlank()) return@listener

                        // Palavra-chave “A recolher” → promover último semáforo mostrado (mantido)
                        if (!inFlightPickupLatch && containsPickupKeyword(extractedText)) {
                            val now = System.currentTimeMillis()
                            if (now - lastStartPromptMs >= START_PROMPT_DEBOUNCE_MS) {
                                lastStartPromptMs = now
                                try {
                                    val intent = Intent(this, OverlayService::class.java).apply {
                                        action = OverlayService.ACTION_CONFIRM_START_TRACKING
                                    }
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                        startForegroundService(intent)
                                    } else {
                                        startService(intent)
                                    }
                                } catch (_: Exception) { }
                            }
                        }

                        val offerData = imageAnalysisUtils.analyzeTextForOffer(
                            visionText, bitmapCopyForListeners.width, bitmapCopyForListeners.height)
                        if (offerData == null || !offerData.isValid()) return@listener

                        // Bufferiza CANDIDATO (só em memória, não salva)
                        try {
                            lastCandidateSignature = createOfferSignature(offerData)
                            lastCandidateTime = System.currentTimeMillis()
                            try {
                                lastCandidateBitmap = bitmapCopyForListeners.copy(
                                    bitmapCopyForListeners.config ?: Bitmap.Config.ARGB_8888, false
                                )
                            } catch (_: Exception) { lastCandidateBitmap = null }
                        } catch (_: Exception) { }

                        val stable = stability.add(offerData) ?: return@listener

                        when (classify(stable)) {
                            Verdict.INVALID -> return@listener
                            Verdict.SUSPECT -> {
                                val sig = createOfferSignature(stable)
                                val now = System.currentTimeMillis()
                                val canRecapture = (sig != lastDetectedOfferSignature) || (now - lastOfferDetectedTime > 2000L)
                                if (canRecapture) {
                                    lastDetectedOfferSignature = sig
                                    lastOfferDetectedTime = now
                                    stability.clear()
                                    processingHandler.postDelayed({
                                        imageReader?.let { rdr -> processAvailableImage(rdr) }
                                    }, 600L)
                                }
                                return@listener
                            }
                            Verdict.VALID -> {
                                val offerSignature = createOfferSignature(stable)
                                val since = System.currentTimeMillis() - lastOfferDetectedTime
                                if (since < OFFER_PROCESSING_LOCK_PERIOD_MS && offerSignature == lastDetectedOfferSignature) {
                                    return@listener
                                }
                                lastOfferDetectedTime = System.currentTimeMillis()
                                lastDetectedOfferSignature = offerSignature

                                // **Persistência de screenshots desativada**: limpa buffers e segue
                                processingHandler.post {
                                    recyclePotentialSaveBitmap("save off (hard)")
                                    try { nextOfferBitmap?.recycle() } catch (_: Exception) {}
                                    nextOfferBitmap = null; nextOfferSignature = null
                                }

                                Handler(Looper.getMainLooper()).post {
                                    OfferManager.getInstance(applicationContext).processOffer(stable)
                                }
                            }
                        }
                    }
                    .addOnFailureListener { processingHandler.post { recyclePotentialSaveBitmap("falha OCR") } }
                    .addOnCompleteListener {
                        if (bitmapCopyForListeners?.isRecycled == false) bitmapCopyForListeners.recycle()
                        isProcessingImage.compareAndSet(true, false)
                    }
            } catch (_: IllegalStateException) {
                if (bitmapCopyForListeners?.isRecycled == false) bitmapCopyForListeners.recycle()
                processingHandler.post { recyclePotentialSaveBitmap("ocr throttled") }
                isProcessingImage.compareAndSet(true, false)
                return
            }
        } catch (_: OutOfMemoryError) {
            bitmapCopyForListeners?.recycle(); bitmapPreprocessed?.recycle(); bitmapAfterResize?.recycle(); bitmapToProcess.recycle()
            processingHandler.post { recyclePotentialSaveBitmap("OOM") }
            isProcessingImage.compareAndSet(true, false)
        } catch (_: Exception) {
            bitmapCopyForListeners?.recycle(); bitmapPreprocessed?.recycle(); bitmapAfterResize?.recycle(); bitmapToProcess.recycle()
            processingHandler.post { recyclePotentialSaveBitmap("exceção") }
            isProcessingImage.compareAndSet(true, false)
        }
    }

    private fun containsPickupKeyword(text: String): Boolean {
        val s = stripAccentsLower(text).replace(Regex("\\s+"), " ").trim()
        return Regex("\\b[aà]\\s+recolher\\b").containsMatchIn(s)
    }

    private fun stripAccentsLower(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .lowercase(Locale.ROOT)

    private fun createOfferSignature(offerData: OfferData): String {
        val v = offerData.value.replace(",", ".")
        val pd = offerData.pickupDistance.replace(",", ".").toDoubleOrNull()?.let { String.format(Locale.US, "%.2f", it) } ?: "0"
        val td = offerData.tripDistance.replace(",", ".").toDoubleOrNull()?.let { String.format(Locale.US, "%.2f", it) } ?: "0"
        val pt = offerData.pickupDuration.toIntOrNull()?.toString() ?: "0"
        val tt = offerData.tripDuration.toIntOrNull()?.toString() ?: "0"
        return "v:$v|pd:$pd|td:$td|pt:$pt|tt:$tt"
    }

    private fun recyclePotentialSaveBitmap(reason: String = "") {
        val b = lastBitmapForPotentialSave
        if (b != null && !b.isRecycled) { b.recycle() }
        lastBitmapForPotentialSave = null
        bufferedOfferSignature = null
    }

    // Mantido (debug), mas não utilizado no fluxo atual de persistência
    private fun saveScreenshotToGallery(bitmapToSave: Bitmap?, prefix: String) {
        if (bitmapToSave == null || bitmapToSave.isRecycled) return
        val timeStamp = SimpleDateFormat("yyMMdd_HHmmss_SSS", Locale.getDefault()).format(Date())
        val fileName = "SmartDriver_${prefix}_${timeStamp}_${screenshotCounter++}.jpg"

        val cv = android.content.ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SmartDriverDebug")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val imageUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
        var success = false

        if (imageUri != null) {
            try {
                contentResolver.openOutputStream(imageUri)?.use { os ->
                    bitmapToSave.compress(Bitmap.CompressFormat.JPEG, 90, os)
                    success = true
                }
            } catch (_: Exception) {
                try { contentResolver.delete(imageUri, null, null) } catch (_: Exception) {}
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && imageUri != null && success) {
            cv.clear(); cv.put(MediaStore.Images.Media.IS_PENDING, 0)
            try { contentResolver.update(imageUri, cv, null, null) } catch (_: Exception) {}
        }
        if (!bitmapToSave.isRecycled) bitmapToSave.recycle()
    }

    private fun createNotification(contentText: String): Notification {
        createNotificationChannel()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SmartDriver")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_stat_smartdriver)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(contentText: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, createNotification(contentText))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notificação persistente do serviço de captura SmartDriver"
                enableLights(false); enableVibration(false); setShowBadge(false)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isRunning.set(false)

        userStopRequested = true
        stopScreenCaptureInternal()

        try { unregisterReceiver(overlayEventsReceiver) } catch (_: Exception) {}

        if (::processingThread.isInitialized && processingThread.isAlive) {
            try { processingThread.quitSafely(); processingThread.join(500) } catch (_: Exception) { }
        }
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) { }
    }
}
