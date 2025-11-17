package com.example.smartdriver

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.*
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.example.smartdriver.overlay.OverlayService
import com.example.smartdriver.utils.ImageAnalysisUtils
import com.example.smartdriver.utils.OfferData
import com.example.smartdriver.utils.OcrTextRecognizer
import java.text.Normalizer
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

        const val ACTION_FREEZE_OCR = "com.example.smartdriver.screen_capture.FREEZE_OCR"
        const val ACTION_RESUME_AFTER_PREVIEW = "com.example.smartdriver.screen_capture.RESUME_AFTER_PREVIEW"

        const val ACTION_OCR_ENABLE = "com.example.smartdriver.screen_capture.OCR_ENABLE"
        const val ACTION_OCR_DISABLE = "com.example.smartdriver.screen_capture.OCR_DISABLE"
        const val ACTION_OCR_REINIT = "com.example.smartdriver.screen_capture.OCR_REINIT"

        // [NOVO] Ações para pausa temporária (Menu Aberto)
        const val ACTION_PAUSE_CAPTURE = "com.example.smartdriver.ACTION_PAUSE_CAPTURE"
        const val ACTION_RESUME_CAPTURE = "com.example.smartdriver.ACTION_RESUME_CAPTURE"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val KEY_SAVE_IMAGES = "save_images"

        @JvmStatic val isRunning = AtomicBoolean(false)

        // Ajustado para processar mais frames por segundo
        private const val OCR_THROTTLE_MS = 150L
        private const val OCR_IMAGE_SCALE_FACTOR = 1.0f
        private const val OFFER_PROCESSING_LOCK_PERIOD_MS = 1500L // Reduzido para não perder ofertas
        private const val HASH_CACHE_DURATION_MS = 400L // Cache menor
        private const val KEY_OCR_DESIRED = "ocr_desired_on"

        private const val START_PROMPT_DEBOUNCE_MS = 3500L
    }

    // --- Hardware de Captura ---
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    // --- Estado e Threads ---
    private val isCapturingActive = AtomicBoolean(false)
    private val isProcessingImage = AtomicBoolean(false)
    private var lastProcessingStartTime = 0L

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var processingThread: HandlerThread
    private lateinit var processingHandler: Handler

    @Volatile private var ocrEnabled: Boolean = true
    private lateinit var preferences: SharedPreferences
    @Volatile private var userStopRequested: Boolean = false
    @Volatile private var waitingForReauth: Boolean = false

    // [NOVO] Pausa temporária quando o menu está aberto
    @Volatile private var isPausedByMenu: Boolean = false

    // --- Otimização de Memória (Single Pass) ---
    private var workingBitmap: Bitmap? = null
    private val processPaint = Paint()
    private val processColorMatrix = ColorMatrix()

    // --- OCR e Análise ---
    private val imageAnalysisUtils = ImageAnalysisUtils()
    private val ocr: OcrTextRecognizer = OcrTextRecognizer.getInstance()

    // --- Variáveis de Sessão ---
    @Volatile private var inFlightPickupLatch: Boolean = false
    @Volatile private var currentTripId: String? = null

    // Cache e Deduplicação
    @Volatile private var lastOfferDetectedTime = 0L
    private var lastDetectedOfferSignature: String? = null
    private var lastScreenHash: Int? = null
    private var lastHashTime: Long = 0L

    @Volatile private var ocrFreezeUntil = 0L
    @Volatile private var lastStartPromptMs: Long = 0L

    // Buffers
    @Volatile private var lastBitmapForPotentialSave: Bitmap? = null
    @Volatile private var lastCandidateBitmap: Bitmap? = null
    @Volatile private var lastCandidateSignature: String? = null
    @Volatile private var lastCandidateTime: Long = 0L

    // --- Receivers ---
    private val screenUnlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_USER_PRESENT) {
                if (waitingForReauth && !userStopRequested) {
                    triggerPermissionRequest()
                }
            }
        }
    }

    private val overlayEventsReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            when (i?.action) {
                OverlayService.ACTION_EVT_TRACKING_STARTED -> {
                    inFlightPickupLatch = true
                    currentTripId = try { i.getStringExtra(OverlayService.EXTRA_TRIP_ID) } catch (_: Exception) { null }
                    processingHandler.post {
                        recyclePotentialSaveBitmap("tracking started")
                        try { if (lastCandidateBitmap != null && !lastCandidateBitmap!!.isRecycled) lastCandidateBitmap!!.recycle() } catch (_: Exception) {}
                        lastCandidateBitmap = null
                    }
                }
                OverlayService.ACTION_EVT_TRACKING_ENDED -> {
                    inFlightPickupLatch = false
                    currentTripId = null
                    stability.clear()
                    lastDetectedOfferSignature = null
                    recyclePotentialSaveBitmap("tracking ended")
                }
            }
        }
    }

    private fun isOcrAllowedNow(): Boolean = System.currentTimeMillis() >= ocrFreezeUntil
    fun freezeOcrFor(ms: Long = 900L) { ocrFreezeUntil = System.currentTimeMillis() + ms }

    // --- Estabilidade Inteligente (Fast Track) ---
    private class StabilityWindow(private val windowMs: Long = 1000L) { // Janela mais curta
        private data class Item(val sig: String, val t: Long, val data: OfferData)
        private val items = ArrayDeque<Item>()

        fun add(candidate: OfferData): OfferData? {
            val now = System.currentTimeMillis()
            val sig = candidateSignature(candidate)

            // Limpar itens antigos
            items.addLast(Item(sig, now, candidate))
            while (items.isNotEmpty() && now - items.first.t > windowMs) items.removeFirst()

            val counts = mutableMapOf<String, Int>()
            for (it in items) counts[it.sig] = (counts[it.sig] ?: 0) + 1

            val bestSig = counts.maxByOrNull { it.value }?.key ?: return null
            val votes = counts[bestSig] ?: 0
            val bestData = items.lastOrNull { it.sig == bestSig }?.data ?: return null

            // Lógica "Fast Track":
            // Se a oferta parece completa (tem preço, dist e tempo), aceita com menos votos (2).
            // Se for uma leitura parcial, exige confirmação (3).
            val isComplete = bestData.value.isNotEmpty() &&
                    (bestData.tripDistance.isNotEmpty() || bestData.tripDuration.isNotEmpty())

            val requiredVotes = if (isComplete) 2 else 3

            return if (votes >= requiredVotes) bestData else null
        }

        private fun candidateSignature(o: OfferData): String {
            fun n(s: String?) = (s ?: "").replace(",", ".").trim()
            return "v:${n(o.value)}|pd:${n(o.pickupDistance)}|td:${n(o.tripDistance)}|pt:${n(o.pickupDuration)}|tt:${n(o.tripDuration)}"
        }
        fun clear() = items.clear()
    }

    private val stability = StabilityWindow(windowMs = 1000L)

    private data class BizRules(
        val minEurPerKm: Double = 0.38,
        val minMinutes: Int = 5,
        val lowFareThreshold: Double = 2.50,
        val shortTotalKm: Double = 8.0,
        val maxPickupRatio: Double = 0.85,
        val maxAbsurdPrice: Double = 200.0
    )
    private val rules = BizRules()
    private enum class Verdict { VALID, SUSPECT, INVALID }

    private fun classify(stable: OfferData): Verdict {
        fun d(s: String?) = s?.replace(",", ".")?.toDoubleOrNull()
        val price = d(stable.value) ?: return Verdict.INVALID

        if (price <= 0.1) return Verdict.INVALID
        if (price > rules.maxAbsurdPrice) return Verdict.INVALID

        return Verdict.VALID
    }

    override fun onCreate() {
        super.onCreate()
        isRunning.set(true)

        preferences = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        ocrEnabled = preferences.getBoolean(KEY_OCR_DESIRED, true)
        userStopRequested = false

        setupProcessPaint()

        processingThread = HandlerThread("ImageProcessingThread", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
        processingHandler = Handler(processingThread.looper)

        getScreenMetrics()
        startForeground(NOTIFICATION_ID, createNotification("SmartDriver ativo", false))

        val filter = IntentFilter(Intent.ACTION_USER_PRESENT)
        registerReceiver(screenUnlockReceiver, filter)

        val flt = IntentFilter().apply {
            addAction(OverlayService.ACTION_EVT_TRACKING_STARTED)
            addAction(OverlayService.ACTION_EVT_TRACKING_ENDED)
        }
        try { registerReceiver(overlayEventsReceiver, flt) } catch (_: Exception) {}

        if (MediaProjectionData.isValid()) {
            setupMediaProjection()
            startScreenCaptureInternal()
        }

        // Watchdog
        mainHandler.postDelayed(object : Runnable {
            override fun run() {
                try {
                    if (isProcessingImage.get()) {
                        if (System.currentTimeMillis() - lastProcessingStartTime > 2000L) {
                            Log.w(TAG, "Watchdog: Processamento encravado. Reset.")
                            isProcessingImage.set(false)
                        }
                    }
                    if (isRunning.get() && ocrEnabled && !userStopRequested &&
                        !waitingForReauth && !isCapturingActive.get() && MediaProjectionData.isValid()) {
                        startScreenCaptureInternal()
                    }
                } catch (_: Exception) {}
                finally {
                    mainHandler.postDelayed(this, 2000L)
                }
            }
        }, 2000L)
    }

    private fun setupProcessPaint() {
        val contrastValue = 1.5f
        val brightnessValue = -(128 * (contrastValue - 1))
        processColorMatrix.set(floatArrayOf(
            contrastValue, 0f, 0f, 0f, brightnessValue,
            0f, contrastValue, 0f, 0f, brightnessValue,
            0f, 0f, contrastValue, 0f, brightnessValue,
            0f, 0f, 0f, 1f, 0f
        ))
        processPaint.colorFilter = ColorMatrixColorFilter(processColorMatrix)
        processPaint.isFilterBitmap = true
        processPaint.isDither = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null &&
            intent.hasExtra(EXTRA_RESULT_CODE) &&
            intent.hasExtra(EXTRA_RESULT_DATA)
        ) {
            val code = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            val data: Intent? = getParcelableExtraCompat(intent, EXTRA_RESULT_DATA, Intent::class.java)

            if (code == Activity.RESULT_OK && data != null) {
                MediaProjectionData.resultCode = code
                MediaProjectionData.resultData = data

                waitingForReauth = false
                userStopRequested = false
                ocrEnabled = true

                setupMediaProjection()
                startScreenCaptureInternal()
            } else {
                updateNotification("Permissão negada.", true)
            }
        }

        when (intent?.action) {
            // [NOVO] Pausa/Retoma pelo Menu da App
            ACTION_PAUSE_CAPTURE -> {
                isPausedByMenu = true
            }
            ACTION_RESUME_CAPTURE -> {
                isPausedByMenu = false
            }

            ACTION_STOP_CAPTURE, ACTION_OCR_DISABLE -> {
                preferences.edit().putBoolean(KEY_OCR_DESIRED, false).apply()
                ocrEnabled = false
                userStopRequested = true
                waitingForReauth = false
                stopCaptureResources()
                updateNotification("SmartDriver (captura parada)", true)
            }

            ACTION_OCR_ENABLE -> {
                preferences.edit().putBoolean(KEY_OCR_DESIRED, true).apply()
                ocrEnabled = true
                userStopRequested = false
                if (MediaProjectionData.isValid()) {
                    setupMediaProjection()
                    startScreenCaptureInternal()
                } else {
                    triggerPermissionRequest()
                }
            }

            ACTION_OCR_REINIT -> {
                if (preferences.getBoolean(KEY_OCR_DESIRED, true)) {
                    ocrEnabled = true
                    userStopRequested = false
                    reinitCapturePipeline()
                } else {
                    ocrEnabled = false
                    updateNotification("SmartDriver (OCR desligado)", true)
                }
            }

            ACTION_CAPTURE_NOW -> {
                if (mediaProjection != null && imageReader != null && isCapturingActive.get()) {
                    processingHandler.post { processAvailableImage(imageReader) }
                }
            }

            ACTION_FREEZE_OCR -> freezeOcrFor(900L)

            ACTION_RESUME_AFTER_PREVIEW -> {
                ocrFreezeUntil = 0L
                if (!isCapturingActive.get() && MediaProjectionData.isValid() && ocrEnabled && !userStopRequested) {
                    startScreenCaptureInternal()
                }
            }
        }
        return START_STICKY
    }

    private fun triggerPermissionRequest() {
        try {
            val i = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("AUTO_REQUEST_PERMISSION", true)
            }
            startActivity(i)
            updateNotification("A solicitar permissão...", false)
        } catch (e: Exception) {
            Log.e(TAG, "Erro triggerPermissionRequest: ${e.message}")
        }
    }

    private fun reinitCapturePipeline() {
        stopCaptureResources()
        stability.clear()
        lastScreenHash = null
        lastDetectedOfferSignature = null
        lastOfferDetectedTime = 0L
        inFlightPickupLatch = false
        isProcessingImage.set(false)
        mainHandler.postDelayed({
            if (MediaProjectionData.isValid()) {
                setupMediaProjection()
                startScreenCaptureInternal()
            }
        }, 250L)
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
                    Log.w(TAG, "MediaProjection onStop() – Sistema parou captura.")
                    stopCaptureResources()
                    MediaProjectionData.clear()
                    waitingForReauth = true
                    updateNotification("Pausa (Ecrã desligado). Toque para retomar.", true)
                }
            }, mainHandler)
            setupImageReader()
        } catch (e: Exception) {
            MediaProjectionData.clear()
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
            waitingForReauth = false
            updateNotification("SmartDriver a monitorizar…", false)
        } catch (e: Exception) {
            isCapturingActive.set(false)
        }
    }

    private fun stopCaptureResources() {
        isCapturingActive.set(false)
        try { virtualDisplay?.release() } catch (_: Exception) {} finally { virtualDisplay = null }
        try { imageReader?.close() } catch (_: Exception) {} finally { imageReader = null }
        try { mediaProjection?.stop() } catch (_: Exception) {} finally { mediaProjection = null }

        try { workingBitmap?.recycle() } catch (_: Exception) {} finally { workingBitmap = null }

        isProcessingImage.set(false)
        lastScreenHash = null
    }

    private fun processAvailableImage(reader: ImageReader?) {
        // [NOVO] Bloqueia processamento se isPausedByMenu for true
        if (reader == null || !isCapturingActive.get() || !ocrEnabled || userStopRequested || isPausedByMenu) {
            try { reader?.acquireLatestImage()?.close() } catch (_: Exception) {}
            return
        }

        if (!isProcessingImage.compareAndSet(false, true)) {
            try { reader.acquireLatestImage()?.close() } catch (_: Exception) {}
            return
        }

        lastProcessingStartTime = System.currentTimeMillis()

        val now = System.currentTimeMillis()
        if (now - lastOfferDetectedTime < 300L) {
            isProcessingImage.set(false)
            try { reader.acquireLatestImage()?.close() } catch (_: Exception) {}
            return
        }

        var image: Image? = null
        var rawBitmap: Bitmap? = null

        try {
            image = reader.acquireLatestImage()
            if (image == null) {
                isProcessingImage.set(false)
                return
            }

            rawBitmap = imageToRawBitmap(image)
            image.close()
            image = null

            if (rawBitmap == null) {
                isProcessingImage.set(false)
                return
            }

            val roi = imageAnalysisUtils.getRegionsOfInterest(rawBitmap.width, rawBitmap.height).first()

            val scale = OCR_IMAGE_SCALE_FACTOR
            val targetWidth = (roi.width() * scale).toInt()
            val targetHeight = (roi.height() * scale).toInt()

            if (workingBitmap == null || workingBitmap?.width != targetWidth || workingBitmap?.height != targetHeight) {
                workingBitmap?.recycle()
                workingBitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
            }

            val canvas = Canvas(workingBitmap!!)
            val srcRect = Rect(roi.left, roi.top, roi.right, roi.bottom)
            val dstRect = Rect(0, 0, targetWidth, targetHeight)

            canvas.drawBitmap(rawBitmap, srcRect, dstRect, processPaint)

            rawBitmap.recycle()
            rawBitmap = null

            val hash = calculateImageHash(workingBitmap!!)
            val tHash = System.currentTimeMillis()
            if (tHash - lastHashTime < HASH_CACHE_DURATION_MS && hash == lastScreenHash) {
                isProcessingImage.set(false)
                return
            }
            lastScreenHash = hash
            lastHashTime = tHash

            val bitmapForOcr = workingBitmap!!.copy(Bitmap.Config.ARGB_8888, false)

            processBitmapForOcr(bitmapForOcr)

        } catch (e: Exception) {
            image?.close()
            rawBitmap?.recycle()
            isProcessingImage.set(false)
            Log.e(TAG, "Erro processAvailableImage: ${e.message}")
        }
    }

    private fun processBitmapForOcr(bitmapForOcr: Bitmap) {
        try {
            // CORREÇÃO AQUI: Removido .toInt(), passando OCR_THROTTLE_MS (Long) diretamente
            ocr.processThrottled(bitmapForOcr, 0, OCR_THROTTLE_MS)
                .addOnSuccessListener listener@{ visionText ->
                    val extractedText = visionText.text
                    if (extractedText.isBlank()) return@listener

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
                        visionText, bitmapForOcr.width, bitmapForOcr.height)

                    if (offerData == null || !offerData.isValid()) return@listener

                    try {
                        lastCandidateSignature = createOfferSignature(offerData)
                        lastCandidateTime = System.currentTimeMillis()
                    } catch (_: Exception) {}

                    val stable = stability.add(offerData) ?: return@listener

                    when (classify(stable)) {
                        Verdict.INVALID -> return@listener
                        Verdict.SUSPECT, Verdict.VALID -> {
                            val offerSignature = createOfferSignature(stable)
                            val since = System.currentTimeMillis() - lastOfferDetectedTime

                            if (since < OFFER_PROCESSING_LOCK_PERIOD_MS && offerSignature == lastDetectedOfferSignature) {
                                return@listener
                            }

                            lastOfferDetectedTime = System.currentTimeMillis()
                            lastDetectedOfferSignature = offerSignature

                            Handler(Looper.getMainLooper()).post {
                                OfferManager.getInstance(applicationContext).processOffer(stable)
                            }
                        }
                    }
                }
                .addOnFailureListener { }
                .addOnCompleteListener {
                    if (!bitmapForOcr.isRecycled) bitmapForOcr.recycle()
                    isProcessingImage.set(false)
                }
        } catch (e: Exception) {
            if (!bitmapForOcr.isRecycled) bitmapForOcr.recycle()
            isProcessingImage.set(false)
        }
    }

    private fun imageToRawBitmap(image: Image): Bitmap? {
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

            bmpPad
        } catch (_: Throwable) { null }
    }

    private fun calculateImageHash(bitmap: Bitmap): Int {
        val scaleSize = 32
        return try {
            val scaledBitmap = Bitmap.createScaledBitmap(bitmap, scaleSize, scaleSize, true)
            val pixels = IntArray(scaleSize * scaleSize)
            scaledBitmap.getPixels(pixels, 0, scaleSize, 0, 0, scaleSize, scaleSize)
            scaledBitmap.recycle()
            pixels.contentHashCode()
        } catch (_: Exception) { bitmap.hashCode() }
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
    }

    private fun createNotification(contentText: String, clickable: Boolean): Notification {
        createNotificationChannel()
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SmartDriver")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_stat_smartdriver)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (clickable) {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("AUTO_REQUEST_PERMISSION", true)
            }
            val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.setContentIntent(pi)
            builder.addAction(R.drawable.ic_stat_smartdriver, "REATIVAR", pi)
        }

        return builder.build()
    }

    private fun updateNotification(contentText: String, clickable: Boolean) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, createNotification(contentText, clickable))
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
        stopCaptureResources()

        try { unregisterReceiver(screenUnlockReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(overlayEventsReceiver) } catch (_: Exception) {}

        if (::processingThread.isInitialized && processingThread.isAlive) {
            try { processingThread.quitSafely(); processingThread.join(500) } catch (_: Exception) { }
        }
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) { }
    }
}