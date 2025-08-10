package com.example.smartdriver // <<< VERIFIQUE O PACKAGE

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Resources
import android.graphics.Bitmap // Import Bitmap
import android.graphics.Canvas // Import Canvas
import android.graphics.ColorMatrix // Import ColorMatrix
import android.graphics.ColorMatrixColorFilter // Import ColorMatrixColorFilter
import android.graphics.Paint // Import Paint
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.example.smartdriver.utils.ImageAnalysisUtils
import com.example.smartdriver.utils.OfferData
import com.example.smartdriver.utils.OcrTextRecognizer // << NOVO: usamos o util de OCR
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val NOTIFICATION_ID = 1001; private const val CHANNEL_ID = "screen_capture_channel"; private const val CHANNEL_NAME = "Screen Capture Service"
        const val ACTION_STOP_CAPTURE = "com.example.smartdriver.screen_capture.STOP"
        const val ACTION_CAPTURE_NOW = "com.example.smartdriver.screen_capture.CAPTURE_NOW"
        const val ACTION_UPDATE_SETTINGS = "com.example.smartdriver.screen_capture.UPDATE_SETTINGS"
        const val ACTION_SAVE_LAST_VALID_OFFER_SCREENSHOT = "com.example.smartdriver.screen_capture.SAVE_LAST_VALID" // <<< NOVA AÇÃO PARA SALVAR
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val KEY_SAVE_IMAGES = "save_images" // Usado por MainActivity e SettingsActivity

        @JvmStatic val isRunning = AtomicBoolean(false)

        // Constantes de controle (mantidas)
        private const val OCR_IMAGE_SCALE_FACTOR = 1.0f
        private const val OFFER_PROCESSING_LOCK_PERIOD_MS = 3000L // Bloqueio interno rápido
        private const val HASH_CACHE_DURATION_MS = 800L
    }

    // Variáveis de Projeção/Captura
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenWidth = 0; private var screenHeight = 0; private var screenDensity = 0

    // Controle de Estado e Threads
    private val isCapturingActive = AtomicBoolean(false)
    private val isProcessingImage = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var processingThread: HandlerThread
    private lateinit var processingHandler: Handler

    // Configurações e Análise
    private lateinit var preferences: SharedPreferences
    @Volatile private var shouldSaveScreenshots = false
    private val imageAnalysisUtils = ImageAnalysisUtils()

    // >>> NOVO: usamos o util centralizado em vez do cliente direto do ML Kit
    private val ocr: OcrTextRecognizer = OcrTextRecognizer.getInstance()

    // Controle de Duplicatas/Recência interno
    @Volatile private var lastOfferDetectedTime = 0L
    private var lastDetectedOfferSignature: String? = null

    // Hash cache
    private var lastScreenHash: Int? = null
    private var lastHashTime: Long = 0L

    // Bitmap pendente para possível salvamento
    @Volatile private var lastBitmapForPotentialSave: Bitmap? = null

    // Outras Variáveis
    private var screenshotCounter = 0
    private var initialResultCode: Int = Activity.RESULT_CANCELED
    private var initialResultData: Intent? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Serviço Criado")
        isRunning.set(true)
        processingThread = HandlerThread("ImageProcessingThread", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
        processingHandler = Handler(processingThread.looper)
        getScreenMetrics()
        preferences = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        shouldSaveScreenshots = preferences.getBoolean(KEY_SAVE_IMAGES, false)
        startForeground(NOTIFICATION_ID, createNotification("SmartDriver ativo"))
        Log.d(TAG, "onCreate: Salvar screenshots = $shouldSaveScreenshots")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: Action=${intent?.action}")

        if (intent?.hasExtra(EXTRA_RESULT_CODE) == true && initialResultCode == Activity.RESULT_CANCELED) {
            initialResultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            initialResultData = getParcelableExtraCompat(intent, EXTRA_RESULT_DATA, Intent::class.java)?.clone() as? Intent
            Log.i(TAG, "Dados projeção recebidos: Code=$initialResultCode, Data=${initialResultData != null}")
            if (initialResultCode != Activity.RESULT_CANCELED && initialResultData != null) {
                if (!isCapturingActive.get()) {
                    MediaProjectionData.resultCode = initialResultCode
                    MediaProjectionData.resultData = initialResultData?.clone() as? Intent
                    setupMediaProjection(); startScreenCaptureInternal()
                }
            } else { Log.e(TAG, "Dados projeção inválidos recebidos! Parando."); stopSelf() }
        }

        when (intent?.action) {
            ACTION_STOP_CAPTURE -> { Log.i(TAG, "Ação STOP_CAPTURE recebida."); stopScreenCaptureInternal(); stopSelf() }
            ACTION_CAPTURE_NOW -> {
                if (mediaProjection != null && imageReader != null && isCapturingActive.get()) {
                    processingHandler.post { processAvailableImage(imageReader) }
                } else { Log.w(TAG, "CAPTURE_NOW ignorado: Serviço não pronto ou captura inativa.") }
            }
            ACTION_SAVE_LAST_VALID_OFFER_SCREENSHOT -> {
                Log.i(TAG, "Recebido pedido para salvar último screenshot válido (ACTION_SAVE_LAST_VALID_OFFER_SCREENSHOT).")
                processingHandler.post {
                    val bitmapToSave = lastBitmapForPotentialSave
                    if (bitmapToSave != null && !bitmapToSave.isRecycled) {
                        Log.d(TAG, ">>> Iniciando salvamento do screenshot da oferta confirmada...")
                        saveScreenshotToGallery(bitmapToSave, "OFERTA_VALIDA")
                        lastBitmapForPotentialSave = null
                    } else {
                        Log.w(TAG, "Nenhum bitmap válido pendente encontrado para salvar ou já foi reciclado.")
                        lastBitmapForPotentialSave = null
                    }
                }
            }
            ACTION_UPDATE_SETTINGS -> {
                Log.d(TAG, "Ação UPDATE_SETTINGS recebida.")
                if (intent.hasExtra(KEY_SAVE_IMAGES)) {
                    val save = intent.getBooleanExtra(KEY_SAVE_IMAGES, false)
                    if (save != shouldSaveScreenshots) {
                        shouldSaveScreenshots = save
                        preferences.edit().putBoolean(KEY_SAVE_IMAGES, save).apply()
                        Log.i(TAG, "'Salvar Screenshots' atualizado para: $save")
                        if (!save) {
                            processingHandler.post { recyclePotentialSaveBitmap("configuração desligada") }
                        }
                    }
                } else { Log.w(TAG,"UPDATE_SETTINGS recebido sem o extra '$KEY_SAVE_IMAGES'") }
            }
            null -> {
                if (initialResultCode != Activity.RESULT_CANCELED && !isCapturingActive.get()){
                    Log.i(TAG, "Comando nulo/sem ação. Tentando (re)iniciar captura com dados existentes...")
                    if (MediaProjectionData.resultCode == Activity.RESULT_CANCELED) {
                        MediaProjectionData.resultCode = initialResultCode
                        MediaProjectionData.resultData = initialResultData?.clone() as? Intent
                    }
                    setupMediaProjection(); startScreenCaptureInternal()
                } else if (!isCapturingActive.get()) { Log.w(TAG, "Comando nulo/sem ação, mas sem dados válidos para iniciar.") }
            }
            else -> { Log.w(TAG, "Ação desconhecida recebida: ${intent.action}") }
        }
        return START_STICKY
    }

    private fun <T : Any?> getParcelableExtraCompat(intent: Intent?, key: String, clazz: Class<T>): T? {
        return intent?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                it.getParcelableExtra(key, clazz)
            } else {
                @Suppress("DEPRECATION") it.getParcelableExtra(key) as? T
            }
        }
    }

    private fun getScreenMetrics() {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            screenWidth = metrics.bounds.width(); screenHeight = metrics.bounds.height()
        } else {
            val displayMetrics = DisplayMetrics()
            @Suppress("DEPRECATION") windowManager.defaultDisplay.getRealMetrics(displayMetrics)
            screenWidth = displayMetrics.widthPixels; screenHeight = displayMetrics.heightPixels
        }
        screenDensity = resources.configuration.densityDpi
        Log.i(TAG, "Screen Metrics: $screenWidth x $screenHeight @ $screenDensity dpi")
        if (screenWidth <= 0 || screenHeight <= 0) {
            Log.w(TAG, "Falha ao obter métricas de tela, usando fallback (1080x1920).");
            screenWidth = 1080; screenHeight = 1920; screenDensity = DisplayMetrics.DENSITY_DEFAULT
        }
    }

    private fun setupMediaProjection() {
        if (mediaProjection != null) { Log.d(TAG, "MediaProjection já configurado."); return }
        val code = MediaProjectionData.resultCode
        val data = MediaProjectionData.resultData
        if (code == Activity.RESULT_CANCELED || data == null) {
            Log.e(TAG, "Não foi possível configurar MediaProjection: Dados inválidos (code=$code, data=${data==null}). Parando serviço.");
            stopSelf(); return
        }
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try {
            mediaProjection = projectionManager.getMediaProjection(code, data)
            if (mediaProjection == null) {
                Log.e(TAG, "Falha ao obter MediaProjection (retornou null). Limpando dados.");
                MediaProjectionData.clear(); stopSelf(); return
            }
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.w(TAG, "MediaProjection parado externamente! Limpando e parando serviço.");
                    stopScreenCaptureInternal(); stopSelf()
                }
            }, mainHandler)
            Log.i(TAG, "MediaProjection configurado com sucesso.");
            setupImageReader()
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao configurar MediaProjection: ${e.message}", e);
            mediaProjection = null; MediaProjectionData.clear(); stopSelf()
        }
    }

    private fun setupImageReader() {
        if (imageReader != null) { Log.d(TAG, "ImageReader já configurado."); return }
        if (screenWidth <= 0 || screenHeight <= 0) { Log.e(TAG,"Não foi possível configurar ImageReader: Dimensões de tela inválidas."); return }
        try {
            imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
            imageReader?.setOnImageAvailableListener({ reader -> processAvailableImage(reader) }, processingHandler)
            Log.i(TAG, "ImageReader configurado: ${screenWidth}x$screenHeight, Formato=RGBA_8888, Buffer=2")
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Erro ao criar ImageReader (Argumento Ilegal): ${e.message}", e); imageReader = null; stopSelf()
        } catch (e: Exception) { Log.e(TAG, "Erro genérico ao criar ImageReader: ${e.message}", e); imageReader = null; stopSelf() }
    }

    private fun startScreenCaptureInternal() {
        if (mediaProjection == null) { Log.e(TAG, "Falha ao iniciar captura: MediaProjection nulo."); setupMediaProjection(); if(mediaProjection == null) return }
        if (imageReader == null) { Log.e(TAG, "Falha ao iniciar captura: ImageReader nulo."); setupImageReader(); if(imageReader == null) return }
        if (isCapturingActive.get()) { Log.d(TAG, "Captura de tela já está ativa."); return }

        Log.i(TAG, "Iniciando VirtualDisplay para captura de tela...")
        try {
            virtualDisplay = mediaProjection!!.createVirtualDisplay(
                "ScreenCapture", screenWidth, screenHeight, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface,
                null,
                processingHandler
            )
            isCapturingActive.set(true); updateNotification("SmartDriver monitorando..."); Log.i(TAG, ">>> Captura de tela INICIADA <<<")
        } catch (e: SecurityException) {
            Log.e(TAG, "Erro de segurança ao criar VirtualDisplay (permissão revogada?): ${e.message}", e); isCapturingActive.set(false); stopSelf()
        } catch (e: Exception) { Log.e(TAG, "Erro genérico ao criar VirtualDisplay: ${e.message}", e); isCapturingActive.set(false); stopSelf() }
    }

    private fun stopScreenCaptureInternal() {
        if (!isCapturingActive.getAndSet(false)) {
            Log.d(TAG, "Captura de tela já estava inativa.");
            MediaProjectionData.clear(); initialResultCode = Activity.RESULT_CANCELED; initialResultData = null;
            return
        }
        Log.w(TAG, "Parando captura de tela...")
        try { virtualDisplay?.release() } catch (e: Exception) { Log.e(TAG,"Erro liberar VD: ${e.message}") } finally { virtualDisplay = null }
        try { imageReader?.close() } catch (e: Exception) { Log.e(TAG,"Erro fechar IR: ${e.message}") } finally { imageReader = null }
        try { mediaProjection?.stop() } catch (e: Exception) { Log.e(TAG,"Erro parar MP: ${e.message}") } finally { mediaProjection = null }

        MediaProjectionData.clear()
        initialResultCode = Activity.RESULT_CANCELED; initialResultData = null
        isProcessingImage.set(false); lastScreenHash = null
        processingHandler.post { recyclePotentialSaveBitmap("captura parada") }

        Log.i(TAG, ">>> Captura de tela PARADA <<<");
        updateNotification("SmartDriver inativo")
    }

    private fun processAvailableImage(reader: ImageReader?) {
        if (reader == null || !isCapturingActive.get()) { return }

        val currentTimeForLockCheck = System.currentTimeMillis()
        if (currentTimeForLockCheck - lastOfferDetectedTime < OFFER_PROCESSING_LOCK_PERIOD_MS / 2) {
            reader.acquireLatestImage()?.close()
            return
        }

        if (!isProcessingImage.compareAndSet(false, true)) {
            reader.acquireLatestImage()?.close()
            return
        }

        var image: Image? = null; var originalBitmap: Bitmap? = null
        try {
            image = reader.acquireLatestImage()
            if (image == null) {
                isProcessingImage.set(false); return
            }
            originalBitmap = imageToBitmap(image)
            image.close(); image = null

            if (originalBitmap == null) { Log.e(TAG, "Falha ao converter Image para Bitmap."); isProcessingImage.set(false); return }

            val hash = calculateImageHash(originalBitmap); val currentTimeForHash = System.currentTimeMillis()
            if (currentTimeForHash - lastHashTime < HASH_CACHE_DURATION_MS && hash == lastScreenHash) {
                originalBitmap.recycle(); originalBitmap = null
                isProcessingImage.set(false); return
            }
            lastScreenHash = hash; lastHashTime = currentTimeForHash

            val roi = imageAnalysisUtils.getRegionsOfInterest(originalBitmap.width, originalBitmap.height).firstOrNull()
            var bitmapToAnalyze: Bitmap? = null; var regionTag = "UNKNOWN_ROI"

            if (roi != null && !roi.isEmpty && roi.width() > 0 && roi.height() > 0) {
                bitmapToAnalyze = imageAnalysisUtils.cropToRegion(originalBitmap, roi)
                if (bitmapToAnalyze != null) {
                    if (!originalBitmap.isRecycled) originalBitmap.recycle(); originalBitmap = null
                    regionTag = "ROI_${roi.top}_${roi.height()}"
                } else {
                    Log.e(TAG, "Falha ao recortar ROI. Usando tela inteira como fallback.");
                    bitmapToAnalyze = originalBitmap
                    originalBitmap = null
                    regionTag = "FULL_SCREEN_CROP_FAIL"
                }
            } else {
                Log.w(TAG, "ROI inválida ou vazia, usando tela inteira.");
                bitmapToAnalyze = originalBitmap
                originalBitmap = null
                regionTag = "FULL_SCREEN_ROI_FAIL"
            }

            if (bitmapToAnalyze != null && !bitmapToAnalyze.isRecycled) {
                processBitmapRegion(bitmapToAnalyze, screenWidth, screenHeight, regionTag)
            } else {
                Log.e(TAG,"Bitmap para análise final é nulo ou já reciclado.");
                originalBitmap?.recycle()
                isProcessingImage.set(false)
            }

        } catch (e: IllegalStateException) {
            Log.w(TAG, "Erro de Estado Ilegal em processAvailableImage (serviço parando?): ${e.message}");
            originalBitmap?.recycle()
            image?.close()
            isProcessingImage.set(false)
        } catch (e: Exception) {
            Log.e(TAG, "Erro GERAL durante processamento da imagem disponível: ${e.message}", e);
            originalBitmap?.recycle()
            image?.close()
            isProcessingImage.set(false)
        }
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        if (image.format != PixelFormat.RGBA_8888) {
            Log.w(TAG,"Formato de imagem inesperado: ${image.format}. Esperado RGBA_8888.");
            return null
        }
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * image.width

        var bitmap: Bitmap? = null
        try {
            val bitmapWithPadding = Bitmap.createBitmap(image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888)
            bitmapWithPadding.copyPixelsFromBuffer(buffer)
            bitmap = if (rowPadding == 0) {
                bitmapWithPadding
            } else {
                val finalBitmap = Bitmap.createBitmap(bitmapWithPadding, 0, 0, image.width, image.height)
                bitmapWithPadding.recycle()
                finalBitmap
            }
        } catch (e: Exception) {
            Log.e(TAG,"Erro ao converter Image para Bitmap: ${e.message}", e); bitmap = null
        }
        return bitmap
    }

    private fun calculateImageHash(bitmap: Bitmap): Int {
        val scaleSize = 64
        return try {
            val scaledBitmap = Bitmap.createScaledBitmap(bitmap, scaleSize, scaleSize, true)
            val pixels = IntArray(scaleSize * scaleSize)
            scaledBitmap.getPixels(pixels, 0, scaleSize, 0, 0, scaleSize, scaleSize)
            scaledBitmap.recycle()
            pixels.contentHashCode()
        } catch (e: Exception) {
            Log.w(TAG,"Erro ao calcular hash da imagem: ${e.message}");
            bitmap.hashCode()
        }
    }

    private fun preprocessBitmapForOcr(originalBitmap: Bitmap?): Bitmap? {
        if (originalBitmap == null || originalBitmap.isRecycled) {
            Log.w(TAG, "Bitmap original nulo ou reciclado, não pode pré-processar.")
            return originalBitmap
        }
        val startTime = System.currentTimeMillis()
        var processedBitmap: Bitmap? = null
        try {
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

            val duration = System.currentTimeMillis() - startTime
            Log.d(TAG, "Pré-processamento (contraste $contrastValue) aplicado em ${duration}ms.")
            return processedBitmap

        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "Erro de Memória (OOM) durante pré-processamento!", e)
            processedBitmap?.recycle()
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Erro durante pré-processamento do bitmap: ${e.message}", e)
            processedBitmap?.recycle()
            return originalBitmap
        }
    }

    private fun processBitmapRegion(bitmapToProcess: Bitmap, originalWidth: Int, originalHeight: Int, regionTag: String) {
        var bitmapAfterResize: Bitmap? = null
        var bitmapPreprocessed: Bitmap? = null
        var bitmapCopyForListeners: Bitmap? = null
        val timeStart = System.currentTimeMillis()

        try {
            val scaleFactor = OCR_IMAGE_SCALE_FACTOR
            if (abs(scaleFactor - 1.0f) > 0.01f) {
                val newWidth = (bitmapToProcess.width * scaleFactor).toInt()
                val newHeight = (bitmapToProcess.height * scaleFactor).toInt()
                if (newWidth > 0 && newHeight > 0) {
                    bitmapAfterResize = Bitmap.createScaledBitmap(bitmapToProcess, newWidth, newHeight, true)
                    Log.v(TAG, "Bitmap redimensionado para OCR: ${newWidth}x${newHeight} ($regionTag)")
                } else { Log.w(TAG, "Dimensões de resize inválidas ($newWidth x $newHeight). Usando original."); bitmapAfterResize = bitmapToProcess }
            } else { bitmapAfterResize = bitmapToProcess }

            if (bitmapAfterResize == null || bitmapAfterResize.isRecycled) {
                Log.e(TAG, "Bitmap pós-resize nulo/reciclado ($regionTag). Abortando processamento da região.");
                if (bitmapToProcess != bitmapAfterResize && bitmapToProcess?.isRecycled == false) bitmapToProcess.recycle()
                isProcessingImage.compareAndSet(true, false); return
            }

            bitmapPreprocessed = preprocessBitmapForOcr(bitmapAfterResize)

            if (bitmapPreprocessed == null || bitmapPreprocessed.isRecycled) {
                Log.e(TAG, "Bitmap OCR nulo/reciclado pós-pré-processamento ($regionTag).");
                if (bitmapAfterResize != bitmapPreprocessed && bitmapAfterResize?.isRecycled == false) bitmapAfterResize.recycle()
                if (bitmapToProcess != bitmapAfterResize && bitmapToProcess?.isRecycled == false) bitmapToProcess.recycle()
                isProcessingImage.compareAndSet(true, false); return
            }

            try {
                bitmapCopyForListeners = bitmapPreprocessed.copy(bitmapPreprocessed.config ?: Bitmap.Config.ARGB_8888, false)
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao COPIAR bitmap pré-processado para listeners ($regionTag): ${e.message}", e)
                bitmapCopyForListeners = null
            }

            if (bitmapPreprocessed != bitmapAfterResize && bitmapAfterResize?.isRecycled == false) {
                bitmapAfterResize.recycle()
            }
            if (bitmapPreprocessed != bitmapToProcess && bitmapToProcess?.isRecycled == false) {
                bitmapToProcess.recycle()
            }
            if (bitmapCopyForListeners != bitmapPreprocessed && bitmapPreprocessed?.isRecycled == false) {
                bitmapPreprocessed.recycle()
            }

            if (bitmapCopyForListeners == null || bitmapCopyForListeners.isRecycled) {
                Log.e(TAG, "Cópia do Bitmap para OCR falhou ou já reciclado ($regionTag). Não pode continuar.")
                isProcessingImage.compareAndSet(true, false)
                recyclePotentialSaveBitmap("falha na cópia para listener")
                return
            }

            // >>>>>>> AQUI MUDA: usamos o util OcrTextRecognizer com throttling <<<<<<<
            try {
                ocr.processThrottled(bitmapCopyForListeners, 0, 350)
                    .addOnSuccessListener listener@{ visionText ->
                        val extractedText = visionText.text
                        Log.d(TAG,"OCR '$regionTag' SUCESSO (len=${extractedText.length})")

                        processingHandler.post { recyclePotentialSaveBitmap("novo resultado OCR recebido") }

                        if (extractedText.isBlank()) {
                            Log.v(TAG, "Texto OCR vazio para '$regionTag'.")
                            return@listener
                        }

                        val currentTime = System.currentTimeMillis()
                        val offerData = imageAnalysisUtils.analyzeTextForOffer(visionText, bitmapCopyForListeners.width, bitmapCopyForListeners.height)

                        if (offerData == null || !offerData.isValid()) {
                            Log.v(TAG,"'$regionTag': Texto OCR não parece ser uma oferta válida.")
                            return@listener
                        }

                        val offerSignature = createOfferSignature(offerData)
                        val timeSinceLastOfferProc = currentTime - lastOfferDetectedTime
                        if (timeSinceLastOfferProc < OFFER_PROCESSING_LOCK_PERIOD_MS && offerSignature == lastDetectedOfferSignature) {
                            Log.d(TAG, "Oferta '$regionTag' ignorada (Bloqueio Rápido Interno): Assinatura repetida muito rápido.");
                            return@listener
                        }
                        lastOfferDetectedTime = currentTime
                        lastDetectedOfferSignature = offerSignature

                        if (shouldSaveScreenshots) {
                            Log.d(TAG, ">>> Preparando bitmap ($regionTag) para potencial salvamento futuro...")
                            try {
                                lastBitmapForPotentialSave = bitmapCopyForListeners.copy(bitmapCopyForListeners.config ?: Bitmap.Config.ARGB_8888, false)
                            } catch (e: Exception) {
                                Log.e(TAG, "Erro ao COPIAR bitmap para salvamento pendente ($regionTag): ${e.message}", e)
                                lastBitmapForPotentialSave = null
                            }
                        } else {
                            processingHandler.post { recyclePotentialSaveBitmap("config save desligada no momento da oferta") }
                        }

                        Log.i(TAG, "!!!! OFERTA POTENCIAL ($regionTag) [${offerSignature}] ENVIANDO para OfferManager... !!!!")
                        mainHandler.post { OfferManager.getInstance(applicationContext).processOffer(offerData) }
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Falha OCR ($regionTag): ${e.message}")
                        processingHandler.post { recyclePotentialSaveBitmap("falha no OCR") }
                    }
                    .addOnCompleteListener {
                        if (bitmapCopyForListeners?.isRecycled == false) {
                            bitmapCopyForListeners.recycle()
                        }
                        isProcessingImage.compareAndSet(true, false)
                        val duration = System.currentTimeMillis() - timeStart
                        Log.d(TAG,"Processamento completo da região '$regionTag' finalizado em ${duration}ms.")
                    }
            } catch (throttle: IllegalStateException) {
                // Chamadas demasiado frequentes — libertar recursos e sair
                Log.v(TAG, "OCR throttled para '$regionTag': ${throttle.message}")
                if (bitmapCopyForListeners.isRecycled.not()) bitmapCopyForListeners.recycle()
                processingHandler.post { recyclePotentialSaveBitmap("ocr throttled") }
                isProcessingImage.compareAndSet(true, false)
                return
            }

        } catch (oom: OutOfMemoryError) {
            Log.e(TAG, "Erro de Memória (OOM) em processBitmapRegion ($regionTag)!", oom)
            bitmapCopyForListeners?.recycle()
            bitmapPreprocessed?.recycle()
            bitmapAfterResize?.recycle()
            bitmapToProcess?.recycle()
            processingHandler.post { recyclePotentialSaveBitmap("OOM") }
            isProcessingImage.compareAndSet(true, false)
        } catch (e: Exception) {
            Log.e(TAG, "Erro GERAL durante processamento da região do bitmap ($regionTag): ${e.message}", e)
            bitmapCopyForListeners?.recycle()
            bitmapPreprocessed?.recycle()
            bitmapAfterResize?.recycle()
            bitmapToProcess?.recycle()
            processingHandler.post { recyclePotentialSaveBitmap("exceção geral") }
            isProcessingImage.compareAndSet(true, false)
        }
    }

    private fun createOfferSignature(offerData: OfferData): String {
        val v = offerData.value.replace(",",".")
        val pd = offerData.pickupDistance.replace(",", ".").toDoubleOrNull()?.let { String.format(Locale.US, "%.2f", it) } ?: "0"
        val td = offerData.tripDistance.replace(",", ".").toDoubleOrNull()?.let { String.format(Locale.US, "%.2f", it) } ?: "0"
        val pt = offerData.pickupDuration.toIntOrNull()?.toString() ?: "0"
        val tt = offerData.tripDuration.toIntOrNull()?.toString() ?: "0"
        return "v:$v|pd:$pd|td:$td|pt:$pt|tt:$tt"
    }

    private fun recyclePotentialSaveBitmap(reason: String = "") {
        val bitmapToRecycle = lastBitmapForPotentialSave
        if (bitmapToRecycle != null && !bitmapToRecycle.isRecycled) {
            Log.d(TAG, "Reciclando bitmap pendente para salvar. Razão: [$reason]")
            bitmapToRecycle.recycle()
        }
        lastBitmapForPotentialSave = null
    }

    private fun saveScreenshotToGallery(bitmapToSave: Bitmap?, prefix: String) {
        if (bitmapToSave == null || bitmapToSave.isRecycled) {
            Log.w(TAG, "Bitmap nulo ou já reciclado fornecido para saveScreenshotToGallery ($prefix).")
            return
        }

        val timeStamp = SimpleDateFormat("yyMMdd_HHmmss_SSS", Locale.getDefault()).format(Date())
        val fileName = "SmartDriver_${prefix}_${timeStamp}_${screenshotCounter++}.jpg"
        Log.d(TAG, "Iniciando salvamento assíncrono de: $fileName")

        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SmartDriverDebug")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        var imageUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        var success = false

        if (imageUri != null) {
            try {
                contentResolver.openOutputStream(imageUri)?.use { outputStream ->
                    bitmapToSave.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
                    success = true
                    Log.i(TAG, "Screenshot '$fileName' salvo com sucesso (URI: $imageUri)")
                } ?: Log.e(TAG, "Falha ao abrir OutputStream para URI: $imageUri")
            } catch (e: IOException) {
                Log.e(TAG, "Erro de IO ao salvar screenshot '$fileName': ${e.message}", e)
            } catch (e: Exception) {
                Log.e(TAG, "Erro GERAL ao salvar screenshot '$fileName': ${e.message}", e)
            } finally {
                if (!success) {
                    try {
                        contentResolver.delete(imageUri, null, null)
                        Log.d(TAG,"URI removido devido a falha no salvamento: $imageUri")
                    } catch (deleteEx: Exception) {
                        Log.e(TAG, "Erro ao tentar remover URI pendente após falha: $imageUri", deleteEx)
                    }
                }
            }
        } else {
            Log.e(TAG, "Falha ao criar URI no MediaStore para '$fileName'.")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && imageUri != null && success) {
            contentValues.clear()
            contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
            try {
                contentResolver.update(imageUri, contentValues, null, null)
            } catch (e: Exception) {
                Log.e(TAG,"Erro ao atualizar IS_PENDING para 0 para '$fileName': ${e.message}")
            }
        }

        if (!bitmapToSave.isRecycled) {
            bitmapToSave.recycle()
            Log.v(TAG,"Bitmap ($prefix) reciclado no final da função saveScreenshotToGallery.")
        }
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
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            notificationManager.notify(NOTIFICATION_ID, createNotification(contentText))
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao atualizar notificação: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notificação persistente do serviço de captura SmartDriver"
                enableLights(false)
                enableVibration(false)
                setShowBadge(false)
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            try {
                notificationManager.createNotificationChannel(channel)
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao criar canal de notificação: ${e.message}")
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.w(TAG, ">>> Serviço de Captura DESTRUÍDO <<<");
        isRunning.set(false)

        if (::processingThread.isInitialized && processingThread.isAlive) {
            processingHandler.post { recyclePotentialSaveBitmap("onDestroy") }
        } else {
            recyclePotentialSaveBitmap("onDestroy - thread inativa")
        }

        stopScreenCaptureInternal()

        if (::processingThread.isInitialized && processingThread.isAlive){
            try {
                processingThread.quitSafely()
                processingThread.join(500)
                Log.d(TAG,"ProcessingThread finalizada no onDestroy (quitSafely).")
            } catch (e: InterruptedException) {
                Log.w(TAG, "Interrompido ao esperar pela finalização da processingThread.")
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao finalizar processingThread: ${e.message}", e)
            }
        }

        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (e: Exception){
            Log.e(TAG, "Erro ao remover notificação foreground: ${e.message}")
        }
    }
}
