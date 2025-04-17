package com.example.smartdriver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.Activity // Necessário para Activity.RESULT_CANCELED
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
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
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.IOException // Para salvar bitmap
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
// Removido import duplicado de MediaProjectionData

// Classe completa ScreenCaptureService
class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val NOTIFICATION_ID = 1001; private const val CHANNEL_ID = "screen_capture_channel"; private const val CHANNEL_NAME = "Screen Capture Service"
        // --- Ações e Chaves ---
        const val ACTION_STOP_CAPTURE = "com.example.smartdriver.screen_capture.STOP"
        const val ACTION_CAPTURE_NOW = "com.example.smartdriver.screen_capture.CAPTURE_NOW" // Disparado pela Acessibilidade
        const val ACTION_UPDATE_SETTINGS = "com.example.smartdriver.screen_capture.UPDATE_SETTINGS"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val KEY_SAVE_IMAGES = "save_images" // Chave para SharedPreferences

        // --- Estado do Serviço ---
        @JvmStatic
        val isRunning = AtomicBoolean(false)

        // --- Configurações ---
        private const val OCR_IMAGE_SCALE_FACTOR = 1.0f // Testando sem escala
        private const val OFFER_PROCESSING_LOCK_PERIOD_MS = 2500L // Evita processar a mesma oferta muito rápido
        private const val HASH_CACHE_DURATION_MS = 1000L // Evita processar telas idênticas muito rápido
    }

    // Variáveis de Projeção e Captura
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenWidth = 0; private var screenHeight = 0; private var screenDensity = 0

    // Controle de Estado e Threads
    private val isCapturingActive = AtomicBoolean(false) // Se a captura está ativa
    private val isProcessingImage = AtomicBoolean(false) // Semáforo para evitar processamento concorrente
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var processingThread: HandlerThread
    private lateinit var processingHandler: Handler

    // Configurações e Análise
    private lateinit var preferences: SharedPreferences
    @Volatile private var shouldSaveScreenshots = false // Flag para salvar imagens
    private val imageAnalysisUtils = ImageAnalysisUtils()
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    // Controle de Duplicatas/Recência
    private var lastOfferDetectedTime = 0L
    private var lastDetectedOfferSignature: String? = null // Assinatura da última oferta válida
    private var lastScreenHash: Int? = null // Hash da última tela processada
    private var lastHashTime = 0L // Timestamp do último hash

    // Contador para nome de arquivo único
    private var screenshotCounter = 0

    // Dados iniciais da projeção
    private var initialResultCode: Int = Activity.RESULT_CANCELED
    private var initialResultData: Intent? = null

    override fun onCreate() {
        super.onCreate(); Log.i(TAG, "Serviço Criado")
        isRunning.set(true)
        processingThread = HandlerThread("ImageProcessingThread", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
        processingHandler = Handler(processingThread.looper)
        getScreenMetrics()
        preferences = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        shouldSaveScreenshots = preferences.getBoolean(KEY_SAVE_IMAGES, false)
        startForeground(NOTIFICATION_ID, createNotification("SmartDriver ativo"))
        Log.d(TAG, "onCreate: Salvar screenshots inicialmente = $shouldSaveScreenshots")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand recebido: Action=${intent?.action}")

        if (intent?.hasExtra(EXTRA_RESULT_CODE) == true && initialResultCode == Activity.RESULT_CANCELED) {
            initialResultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            initialResultData = getParcelableExtraCompat(intent, EXTRA_RESULT_DATA, Intent::class.java)

            Log.i(TAG, "Dados de projeção recebidos: Code=$initialResultCode, Data=${initialResultData != null}")
            if (initialResultCode != Activity.RESULT_CANCELED && initialResultData != null) {
                if (!isCapturingActive.get()) {
                    MediaProjectionData.resultCode = initialResultCode
                    MediaProjectionData.resultData = initialResultData?.clone() as? Intent
                    Log.d(TAG,"Dados de projeção copiados para MediaProjectionData Singleton.")
                    setupMediaProjection()
                    startScreenCaptureInternal()
                }
            } else {
                Log.e(TAG, "Recebidos dados de projeção inválidos! Code=$initialResultCode. Parando serviço.")
                stopSelf()
            }
        }

        when (intent?.action) {
            ACTION_STOP_CAPTURE -> {
                Log.i(TAG, "Recebida ação ACTION_STOP_CAPTURE.")
                stopScreenCaptureInternal()
                stopSelf()
            }
            ACTION_CAPTURE_NOW -> {
                Log.d(TAG, "Recebida ação ACTION_CAPTURE_NOW.")
                if (mediaProjection != null && imageReader != null && isCapturingActive.get()) {
                    captureAndProcessScreenshot()
                } else {
                    Log.w(TAG, "CAPTURE_NOW ignorado: Serviço não pronto ou inativo. (MP=${mediaProjection!=null}, IR=${imageReader!=null}, Active=${isCapturingActive.get()})")
                    if (!isCapturingActive.get() && initialResultCode != Activity.RESULT_CANCELED) {
                        Log.w(TAG,"Tentando reiniciar captura após CAPTURE_NOW ignorado...")
                        setupMediaProjection()
                        startScreenCaptureInternal()
                    }
                }
            }
            ACTION_UPDATE_SETTINGS -> {
                Log.d(TAG, "Recebida ação ACTION_UPDATE_SETTINGS.")
                if (intent.hasExtra(KEY_SAVE_IMAGES)) {
                    val save = intent.getBooleanExtra(KEY_SAVE_IMAGES, false)
                    if (save != shouldSaveScreenshots) {
                        shouldSaveScreenshots = save
                        preferences.edit().putBoolean(KEY_SAVE_IMAGES, save).apply()
                        Log.i(TAG, "Configuração 'Salvar Screenshots' atualizada para: $save")
                    }
                } else {
                    Log.w(TAG,"Ação UPDATE_SETTINGS recebida sem o extra '$KEY_SAVE_IMAGES'")
                }
            }
            null -> {
                if (initialResultCode != Activity.RESULT_CANCELED && !isCapturingActive.get()){
                    Log.i(TAG, "Intent de (re)inicialização recebida (sem ação explícita). Tentando iniciar captura...")
                    setupMediaProjection()
                    startScreenCaptureInternal()
                } else if (!isCapturingActive.get()) {
                    Log.w(TAG, "Intent de (re)inicialização recebida, mas sem dados de projeção válidos. Serviço inativo.")
                }
            }
            else -> {
                Log.w(TAG, "Ação desconhecida ou não tratada recebida: ${intent.action}")
            }
        }
        return START_STICKY
    }

    private fun <T : Any?> getParcelableExtraCompat(intent: Intent?, key: String, clazz: Class<T>): T? {
        return intent?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                it.getParcelableExtra(key, clazz)
            } else {
                @Suppress("DEPRECATION")
                it.getParcelableExtra(key) as? T
            }
        }
    }

    private fun getScreenMetrics() {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            screenWidth = metrics.bounds.width()
            screenHeight = metrics.bounds.height()
            screenDensity = resources.configuration.densityDpi
        } else {
            val displayMetrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(displayMetrics)
            screenWidth = displayMetrics.widthPixels
            screenHeight = displayMetrics.heightPixels
            screenDensity = displayMetrics.densityDpi
        }
        Log.i(TAG, "Screen Metrics: $screenWidth x $screenHeight @ $screenDensity dpi")
        if (screenWidth <= 0 || screenHeight <= 0) {
            Log.w(TAG, "Falha ao obter métricas da tela, usando fallback 1080x1920.")
            screenWidth = 1080; screenHeight = 1920; screenDensity = DisplayMetrics.DENSITY_DEFAULT
        }
    }

    private fun setupMediaProjection() {
        if (mediaProjection != null) { Log.d(TAG, "MediaProjection já configurado."); return }
        val code = initialResultCode
        val data = initialResultData

        if (code == Activity.RESULT_CANCELED || data == null) {
            Log.e(TAG, "Não é possível configurar MediaProjection: Dados inválidos (Code=$code).")
            stopSelf(); return
        }

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try {
            mediaProjection = projectionManager.getMediaProjection(code, data)
            if (mediaProjection == null) {
                Log.e(TAG, "Falha ao obter MediaProjection (retornou null).")
                MediaProjectionData.clear(); stopSelf(); return
            }
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.w(TAG, "MediaProjection parado externamente!")
                    stopScreenCaptureInternal(); stopSelf()
                }
            }, mainHandler)
            Log.i(TAG, "MediaProjection configurado com sucesso.")
            setupImageReader()
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao configurar MediaProjection: ${e.message}", e)
            mediaProjection = null; MediaProjectionData.clear(); stopSelf()
        }
    }

    private fun setupImageReader() {
        if (imageReader != null) { Log.d(TAG, "ImageReader já configurado."); return }
        if (screenWidth <= 0 || screenHeight <= 0) { Log.e(TAG,"Não é possível configurar ImageReader: Dimensões inválidas."); return }
        try {
            imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
            Log.d(TAG, "ImageReader configurado: ${screenWidth}x$screenHeight, Formato=RGBA_8888")
            imageReader?.setOnImageAvailableListener({ reader ->
                processingHandler.post { processAvailableImage(reader) }
            }, processingHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao criar ImageReader: ${e.message}", e)
            imageReader = null; stopSelf()
        }
    }

    private fun startScreenCaptureInternal() {
        if (mediaProjection == null) { Log.e(TAG, "Falha ao iniciar captura: MediaProjection nulo."); setupMediaProjection(); if(mediaProjection == null) return }
        if (imageReader == null) { Log.e(TAG, "Falha ao iniciar captura: ImageReader nulo."); setupImageReader(); if(imageReader == null) return }
        if (isCapturingActive.get()) { Log.d(TAG, "Tentativa de iniciar captura ignorada: já ativa."); return }

        Log.i(TAG, "Iniciando VirtualDisplay para captura de tela...")
        try {
            virtualDisplay = mediaProjection!!.createVirtualDisplay(
                "ScreenCapture", screenWidth, screenHeight, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader!!.surface,
                null, processingHandler
            )
            isCapturingActive.set(true)
            updateNotification("SmartDriver monitorando...")
            Log.i(TAG, "Captura de tela INICIADA.")
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao criar VirtualDisplay: ${e.message}", e)
            isCapturingActive.set(false); stopSelf()
        }
    }

    private fun stopScreenCaptureInternal() {
        if (!isCapturingActive.getAndSet(false)) {
            Log.d(TAG, "Tentativa de parar captura ignorada: já inativa.")
            MediaProjectionData.clear()
            initialResultCode = Activity.RESULT_CANCELED
            initialResultData = null
            return
        }
        Log.w(TAG, "Parando captura de tela...")

        try { virtualDisplay?.release() } catch (e: Exception) { Log.e(TAG, "Erro ao liberar VirtualDisplay: ${e.message}") } finally { virtualDisplay = null }
        try { imageReader?.close() } catch (e: Exception) { Log.e(TAG, "Erro ao fechar ImageReader: ${e.message}") } finally { imageReader = null }
        try { mediaProjection?.stop() } catch (e: Exception) { Log.e(TAG, "Erro ao parar MediaProjection: ${e.message}") } finally { mediaProjection = null }

        initialResultCode = Activity.RESULT_CANCELED
        initialResultData = null
        MediaProjectionData.clear()

        isProcessingImage.set(false)
        lastScreenHash = null
        Log.i(TAG, "Captura de tela PARADA e recursos liberados.")
        updateNotification("SmartDriver inativo")
    }

    private fun captureAndProcessScreenshot() {
        val timeStartCapture = System.currentTimeMillis()
        // Log.d(TAG, "[TIME] captureAndProcessScreenshot solicitado at $timeStartCapture") // Reduzir log
        if (isProcessingImage.get()) {
            Log.d(TAG, "Processamento de imagem anterior ainda em andamento. CAPTURE_NOW ignorado.")
            return
        }
        processingHandler.post {
            // val timeProcessingStart = System.currentTimeMillis()
            // Log.d(TAG, "[TIME] processAvailableImage (BG) iniciado at $timeProcessingStart (Atraso desde solicitação: ${timeProcessingStart-timeStartCapture}ms)") // Reduzir log
            processAvailableImage(imageReader)
        }
    }

    private fun processAvailableImage(reader: ImageReader?) {
        if (reader == null) { Log.w(TAG, "ImageReader nulo em processAvailableImage."); return }
        if (!isProcessingImage.compareAndSet(false, true)) {
            // Log.d(TAG, "Processamento pulado: Imagem anterior ainda sendo processada (isProcessingImage=true).") // Reduzir log
            return
        }

        var image: Image? = null
        var originalBitmap: Bitmap? = null
        val processingStartTime = System.currentTimeMillis()

        try {
            image = reader.acquireLatestImage()
            if (image == null) {
                // Log.w(TAG, "acquireLatestImage retornou null.") // Reduzir log
                isProcessingImage.set(false); return
            }
            originalBitmap = imageToBitmap(image)
            if (originalBitmap == null) {
                Log.e(TAG, "Falha ao converter Image para Bitmap.")
                isProcessingImage.set(false); return
            }

            val hash = calculateImageHash(originalBitmap)
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastHashTime > HASH_CACHE_DURATION_MS) { lastScreenHash = null }
            if (hash == lastScreenHash) {
                // Log.v(TAG, "Hash da imagem ($hash) igual ao anterior recente. Pulando processamento.") // Reduzir log
                originalBitmap.recycle();
                isProcessingImage.set(false); return
            }
            lastScreenHash = hash
            lastHashTime = currentTime
            // Log.d(TAG, "Processando nova imagem (hash: $hash).") // Reduzir log

            val roi = imageAnalysisUtils.getRegionsOfInterest(originalBitmap.width, originalBitmap.height).firstOrNull()
            var bitmapToAnalyze: Bitmap? = null
            var regionTag = "UNKNOWN_ROI"

            if (roi == null || roi.isEmpty || roi.width() <= 0 || roi.height() <= 0) {
                Log.w(TAG, "ROI inválida ou vazia, usando tela inteira como fallback.")
                bitmapToAnalyze = originalBitmap
                regionTag = "FULL_SCREEN_ROI_FAIL"
            } else {
                bitmapToAnalyze = imageAnalysisUtils.cropToRegion(originalBitmap, roi)
                if (bitmapToAnalyze != null) {
                    // Log.d(TAG, "Processando bitmap recortado para ROI: ${roi.flattenToString()}") // Reduzir log
                    if (!originalBitmap.isRecycled) { originalBitmap.recycle(); /* Log.v(TAG, "Bitmap original reciclado após recorte para ROI.") */ }
                    regionTag = "ROI_${roi.top}_${roi.height()}"
                } else {
                    Log.e(TAG, "Falha ao recortar para ROI. Usando tela inteira.")
                    bitmapToAnalyze = originalBitmap
                    regionTag = "FULL_SCREEN_CROP_FAIL"
                }
            }

            if (bitmapToAnalyze != null) {
                processBitmapRegion(bitmapToAnalyze, screenWidth, screenHeight, regionTag)
            } else {
                Log.e(TAG,"Erro crítico: bitmapToAnalyze é nulo antes de chamar processBitmapRegion.")
                if (originalBitmap != null && !originalBitmap.isRecycled) { originalBitmap.recycle() }
                isProcessingImage.set(false)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Erro GERAL em processAvailableImage: ${e.message}", e)
            if (originalBitmap != null && !originalBitmap.isRecycled) { originalBitmap.recycle() }
            isProcessingImage.set(false)
        } finally {
            try { image?.close() }
            catch (e: IllegalStateException){ Log.w(TAG, "Erro ao fechar image (já fechada?): ${e.message}") }
            // val procTime = System.currentTimeMillis() - processingStartTime
            // Log.d(TAG,"[TIME] processAvailableImage (total BG) demorou ${procTime}ms") // Reduzir log
        }
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        if (image.format != PixelFormat.RGBA_8888) {
            Log.w(TAG,"Formato de imagem inesperado: ${image.format}. Esperado RGBA_8888.")
            return null
        }
        val planes = image.planes; val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride; val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * image.width

        return try {
            val bitmapWithPadding = Bitmap.createBitmap(image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888)
            bitmapWithPadding.copyPixelsFromBuffer(buffer)
            if (rowPadding == 0) bitmapWithPadding
            else {
                val bitmap = Bitmap.createBitmap(bitmapWithPadding, 0, 0, image.width, image.height)
                bitmapWithPadding.recycle()
                bitmap
            }
        } catch (e: Exception) {
            Log.e(TAG,"Erro ao converter Image para Bitmap: ${e.message}", e); null
        }
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
            Log.w(TAG,"Erro ao calcular hash de imagem redimensionada: ${e.message}"); bitmap.hashCode()
        }
    }

    private fun processBitmapRegion(bitmapToProcess: Bitmap, originalWidth: Int, originalHeight: Int, regionTag: String) {
        var finalBitmapForOcr: Bitmap? = null
        val timeStart = System.currentTimeMillis()

        try {
            val scaleFactor = OCR_IMAGE_SCALE_FACTOR
            if (Math.abs(scaleFactor - 1.0f) > 0.01f) { // Use Math.abs
                val newWidth = (bitmapToProcess.width * scaleFactor).toInt()
                val newHeight = (bitmapToProcess.height * scaleFactor).toInt()
                // val timeResizeStart = System.currentTimeMillis() // Reduzir Log

                if (newWidth > 0 && newHeight > 0) {
                    finalBitmapForOcr = Bitmap.createScaledBitmap(bitmapToProcess, newWidth, newHeight, true)
                    // val timeResizeEnd = System.currentTimeMillis()
                    // Log.d(TAG, "[TIME] Redimensionamento ($regionTag) para ${newWidth}x$newHeight demorou ${timeResizeEnd - timeResizeStart}ms") // Reduzir Log
                    if (!bitmapToProcess.isRecycled) { bitmapToProcess.recycle(); /* Log.v(TAG, "Bitmap original ($regionTag) reciclado pós-resize.") */ }
                } else {
                    Log.w(TAG, "Dimensões de resize inválidas ($newWidth x $newHeight) para $regionTag. Usando bitmap sem redimensionar.")
                    finalBitmapForOcr = bitmapToProcess
                }
            } else {
                // Log.d(TAG, "Fator de escala $scaleFactor próximo de 1.0, OCR usará bitmap $regionTag sem redimensionar.") // Reduzir Log
                finalBitmapForOcr = bitmapToProcess
            }

            if (finalBitmapForOcr == null || finalBitmapForOcr!!.isRecycled) { // Usar !! após checagem de null
                Log.e(TAG, "Bitmap para OCR é nulo ou reciclado ($regionTag). Abortando OCR.")
                isProcessingImage.compareAndSet(true, false); return
            }

            val timeOcrStart = System.currentTimeMillis()
            // Log.d(TAG, "[TIME] Iniciando OCR ($regionTag - ${finalBitmapForOcr!!.width}x${finalBitmapForOcr!!.height}) at $timeOcrStart") // Reduzir log
            val inputImage = InputImage.fromBitmap(finalBitmapForOcr!!, 0) // Usar !! aqui também

            // >>>>> ESTA É A PARTE CRUCIAL PARA A CORREÇÃO <<<<<
            // Criamos uma referência final ANTES dos listeners
            val bitmapParaListeners = finalBitmapForOcr

            textRecognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    val timeOcrEnd = System.currentTimeMillis()
                    // Log.i(TAG, "[TIME] OCR ($regionTag) SUCESSO. Demorou ${timeOcrEnd - timeOcrStart}ms") // Reduzir Log
                    val extractedText = visionText.text
                    if (extractedText.isNotBlank()) {
                        // Log.d(TAG,"Texto extraído ($regionTag, ${extractedText.length} chars). Analisando...") // Reduzir Log
                        val currentTime = System.currentTimeMillis()
                        val timeSinceLastOffer = currentTime - lastOfferDetectedTime

                        val timeAnalysisStart = System.currentTimeMillis()
                        val offerData = imageAnalysisUtils.analyzeTextForOffer(extractedText)
                        // val timeAnalysisEnd = System.currentTimeMillis(); Log.d(TAG, "[TIME] Análise texto demorou ${timeAnalysisEnd-timeAnalysisStart}ms") // Reduzir Log

                        if (offerData != null && offerData.isValid()) {
                            val offerSignature = createOfferSignature(offerData)
                            if (timeSinceLastOffer >= OFFER_PROCESSING_LOCK_PERIOD_MS || offerSignature != lastDetectedOfferSignature) {
                                Log.i(TAG, "!!!! OFERTA VÁLIDA DETECTADA ($regionTag) !!!! Processando...")
                                // Log.i(TAG, "   Valor: ${offerData.value}€, Dist(P): ${offerData.pickupDistance}, Dist(T): ${offerData.tripDistance}, Tempo(P): ${offerData.pickupDuration}, Tempo(T): ${offerData.tripDuration}") // Reduzir Log
                                lastOfferDetectedTime = currentTime
                                lastDetectedOfferSignature = offerSignature

                                // Salva a imagem AQUI, somente se for oferta válida e se a opção estiver ativa
                                if (shouldSaveScreenshots) {
                                    try {
                                        // Usa a referência final 'bitmapParaListeners'
                                        if (!bitmapParaListeners.isRecycled) {
                                            val bitmapToSave = bitmapParaListeners.copy(bitmapParaListeners.config ?: Bitmap.Config.ARGB_8888, false) // Usa config padrão se nulo
                                            saveScreenshotToGallery(bitmapToSave, "OFERTA_${regionTag}")
                                        } else {
                                            Log.w(TAG,"Não foi possível salvar screenshot: bitmapParaListeners já reciclado.")
                                        }
                                    } catch (e: Exception) {
                                        Log.e(TAG,"Erro ao COPIAR bitmap para salvar: ${e.message}", e)
                                    }
                                }

                                val t0 = System.currentTimeMillis()
                                mainHandler.post {
                                    // Log.d(TAG, "[TIME] Enviando para OfferManager at $t0") // Reduzir Log
                                    OfferManager.getInstance(applicationContext).processOffer(offerData)
                                }

                            } else {
                                // Log.d(TAG, "Oferta válida ignorada ($regionTag): Bloqueio ativo ou assinatura idêntica.") // Reduzir Log
                            }
                        } else { /* Log.d(TAG, "Nenhuma oferta válida encontrada no texto ($regionTag).") */ } // Reduzir Log
                    } else { /* Log.d(TAG, "Nenhum texto extraído da região ($regionTag).") */ } // Reduzir Log
                }
                .addOnFailureListener { e ->
                    val timeOcrEnd = System.currentTimeMillis()
                    Log.e(TAG, "[TIME] Falha OCR ($regionTag) após ${timeOcrEnd - timeOcrStart}ms: ${e.message}")
                }
                .addOnCompleteListener {
                    // Libera o bitmap usado pelo OCR (usando a referência final)
                    if (!bitmapParaListeners.isRecycled) {
                        bitmapParaListeners.recycle()
                        // Log.v(TAG,"BitmapParaListeners ($regionTag) reciclado no onComplete.") // Reduzir Log
                    } else {
                        // Log.d(TAG,"BitmapParaListeners ($regionTag) já estava reciclado antes do onComplete.") // Reduzir Log
                    }
                    isProcessingImage.compareAndSet(true, false)
                    // val timeTotal = System.currentTimeMillis() - timeStart
                    // Log.d(TAG,"[TIME] Processamento da região ($regionTag) concluído em ${timeTotal}ms. isProcessingImage=false") // Reduzir Log
                }

        } catch (e: Exception) {
            Log.e(TAG, "Erro GERAL em processBitmapRegion ($regionTag): ${e.message}", e)
            if (finalBitmapForOcr != null && !finalBitmapForOcr.isRecycled) {
                finalBitmapForOcr.recycle(); Log.w(TAG,"BitmapForOcr ($regionTag) reciclado no CATCH.")
            }
            isProcessingImage.compareAndSet(true, false)
        }
    }


    private fun createOfferSignature(offerData: OfferData): String {
        val v = offerData.value.replace(",","."); val pd = offerData.pickupDistance.ifBlank{"0"}
        val td = offerData.tripDistance.ifBlank{"0"}; val pt = offerData.pickupDuration.ifBlank{"0"}
        val tt = offerData.tripDuration.ifBlank{"0"}; return "v:$v|pd:$pd|td:$td|pt:$pt|tt:$tt"
    }

    private fun saveScreenshotToGallery(bitmap: Bitmap?, prefix: String) {
        if (!shouldSaveScreenshots) { bitmap?.recycle(); return }
        if (bitmap == null || bitmap.isRecycled) { Log.w(TAG, "Tentativa de salvar bitmap nulo ou reciclado ($prefix)."); return }

        processingHandler.post {
            val timeStamp = SimpleDateFormat("yyMMdd_HHmmss_SSS", Locale.getDefault()).format(Date())
            val fileName = "SmartDriver_${prefix}_${timeStamp}_${screenshotCounter++}.jpg"
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

            if (imageUri == null) {
                Log.e(TAG, "Falha ao criar URI para salvar imagem ($fileName).")
            } else {
                try {
                    contentResolver.openOutputStream(imageUri)?.use { outputStream ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
                        success = true
                        // Log.d(TAG, "Screenshot salvo com sucesso: $fileName (URI: $imageUri)") // Reduzir Log
                    } ?: Log.e(TAG, "Falha ao abrir OutputStream para URI: $imageUri")
                } catch (e: IOException) {
                    Log.e(TAG, "Erro de IO ao salvar screenshot ($fileName): ${e.message}", e)
                } catch (e: Exception) {
                    Log.e(TAG, "Erro desconhecido ao salvar screenshot ($fileName): ${e.message}", e)
                } finally {
                    if (!success) {
                        try { contentResolver.delete(imageUri, null, null) }
                        catch (e: Exception) { Log.w(TAG,"Erro ao deletar URI pendente após falha no salvamento: $imageUri") }
                    }
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && imageUri != null && success) {
                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                try { contentResolver.update(imageUri, contentValues, null, null) }
                catch (e: Exception) { Log.e(TAG,"Erro ao atualizar IS_PENDING para 0 ($fileName): ${e.message}") }
            }

            if (!bitmap.isRecycled) {
                bitmap.recycle()
                // Log.v(TAG,"Bitmap salvo ($prefix) reciclado.") // Reduzir Log
            }
        }
    }

    private fun createNotification(contentText: String): Notification {
        createNotificationChannel()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SmartDriver")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notification_icon) // <<< USA ÍCONE DE NOTIFICAÇÃO
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(contentText: String) {
        val notification = createNotification(contentText)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try { notificationManager.notify(NOTIFICATION_ID, notification) }
        catch (e: Exception) { Log.e(TAG, "Erro ao atualizar notificação: ${e.message}") }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Notificação do Serviço de Captura SmartDriver"; enableLights(false); enableVibration(false); setShowBadge(false) }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            try { notificationManager.createNotificationChannel(channel) }
            catch (e: Exception) { Log.e(TAG, "Erro ao criar canal de notificação: ${e.message}") }
        }
    }

    override fun onBind(intent: Intent?): IBinder? { return null }

    override fun onDestroy() {
        Log.w(TAG, "Serviço de Captura DESTRUÍDO")
        isRunning.set(false)
        stopScreenCaptureInternal()
        if(::processingThread.isInitialized){ processingThread.quitSafely(); /* Log.d(TAG,"ProcessingThread finalizada.") */ } // Reduzir Log
        super.onDestroy()
    }
}