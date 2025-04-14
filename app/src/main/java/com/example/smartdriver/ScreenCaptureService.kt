package com.example.smartdriver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
// --- IMPORT ADICIONADO ---
import android.app.Activity // <<< Necessário para Activity.RESULT_CANCELED
// --- FIM IMPORT ADICIONADO ---
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import com.example.smartdriver.MediaProjectionData

class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val NOTIFICATION_ID = 1001; private const val CHANNEL_ID = "screen_capture_channel"; private const val CHANNEL_NAME = "Screen Capture Service"
        // --- Ações e Chaves ---
        const val ACTION_STOP_CAPTURE = "com.example.smartdriver.screen_capture.STOP"
        const val ACTION_CAPTURE_NOW = "com.example.smartdriver.screen_capture.CAPTURE_NOW"
        const val ACTION_UPDATE_SETTINGS = "com.example.smartdriver.screen_capture.UPDATE_SETTINGS"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val KEY_SAVE_IMAGES = "save_images"

        // --- Estado do Serviço ---
        @JvmStatic
        val isRunning = AtomicBoolean(false)

        // --- Configurações ---
        private const val OCR_IMAGE_SCALE_FACTOR = 1.0f
        private const val OFFER_PROCESSING_LOCK_PERIOD_MS = 2500L
        private const val HASH_CACHE_DURATION_MS = 1000L
    }

    private var mediaProjection: MediaProjection? = null; private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null; private var screenWidth = 0; private var screenHeight = 0; private var screenDensity = 0
    private val isCapturingActive = AtomicBoolean(false); private val isProcessingImage = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper()); private lateinit var processingThread: HandlerThread; private lateinit var processingHandler: Handler
    private lateinit var preferences: SharedPreferences; private var shouldSaveScreenshots = false
    private val imageAnalysisUtils = ImageAnalysisUtils(); private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var lastOfferDetectedTime = 0L; private var lastDetectedOfferSignature: String? = null
    private var lastScreenHash: Int? = null; private var lastHashTime = 0L
    private var screenshotCounter = 0

    // --- Variáveis para guardar dados da intent inicial ---
    private var initialResultCode: Int = Activity.RESULT_CANCELED // <<< USA Activity
    private var initialResultData: Intent? = null

    override fun onCreate() {
        super.onCreate(); Log.i(TAG, "Serviço Criado")
        isRunning.set(true)
        processingThread = HandlerThread("ImageProcessingThread", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
        processingHandler = Handler(processingThread.looper); getScreenMetrics()
        preferences = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        shouldSaveScreenshots = preferences.getBoolean(KEY_SAVE_IMAGES, false)
        startForeground(NOTIFICATION_ID, createNotification("SmartDriver ativo"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: ${intent?.action}")

        if (intent?.hasExtra(EXTRA_RESULT_CODE) == true && initialResultCode == Activity.RESULT_CANCELED) { // <<< USA Activity
            initialResultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) // <<< USA Activity
            // Tenta obter o Parcelable da forma correta dependendo da versão do SDK
            initialResultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_RESULT_DATA)
            }

            Log.i(TAG, "Dados de projeção recebidos: Code=$initialResultCode, Data=${initialResultData != null}")
            if (initialResultCode != Activity.RESULT_CANCELED && initialResultData != null) { // <<< USA Activity
                if (!isCapturingActive.get()) {
                    setupMediaProjection()
                    startScreenCaptureInternal()
                }
            } else {
                Log.e(TAG, "Recebidos dados de projeção inválidos! Code=$initialResultCode")
                stopSelf() // Para o serviço se os dados não forem válidos
            }
        }


        when (intent?.action) {
            ACTION_STOP_CAPTURE -> { stopScreenCaptureInternal(); stopSelf() }
            ACTION_CAPTURE_NOW -> if (mediaProjection != null && imageReader != null && isCapturingActive.get()) { captureAndProcessScreenshot() } else { Log.w(TAG, "CAPTURE_NOW ignorado: não pronto/ativo."); if (!isCapturingActive.get() && initialResultCode != Activity.RESULT_CANCELED) { Log.d(TAG,"Tentando reiniciar captura..."); setupMediaProjection(); startScreenCaptureInternal() } } // <<< USA Activity
            ACTION_UPDATE_SETTINGS -> {
                if (intent.hasExtra(KEY_SAVE_IMAGES)) {
                    val save = intent.getBooleanExtra(KEY_SAVE_IMAGES, false)
                    if (save != shouldSaveScreenshots) {
                        shouldSaveScreenshots = save
                        preferences.edit().putBoolean(KEY_SAVE_IMAGES, save).apply()
                        Log.i(TAG, "Configuração Salvar Screenshots atualizada para: $save")
                    }
                } else {
                    Log.w(TAG,"Ação UPDATE_SETTINGS recebida sem o extra '$KEY_SAVE_IMAGES'")
                }
            }
            else -> {
                if (intent?.action == null && initialResultCode != Activity.RESULT_CANCELED && !isCapturingActive.get()){ // <<< USA Activity
                    Log.d(TAG, "Intent de inicialização (sem ação explícita), iniciando captura...")
                    setupMediaProjection()
                    startScreenCaptureInternal()
                } else if (intent?.action != null) {
                    Log.w(TAG, "Ação desconhecida ou não tratada: ${intent.action}")
                }
            }
        }
        return START_STICKY
    }

    private fun getScreenMetrics() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager; val dm = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { val m = wm.currentWindowMetrics; screenWidth = m.bounds.width(); screenHeight = m.bounds.height(); screenDensity = resources.configuration.densityDpi }
        else { @Suppress("DEPRECATION") val d = wm.defaultDisplay; @Suppress("DEPRECATION") d.getMetrics(dm); screenWidth = dm.widthPixels; screenHeight = dm.heightPixels; screenDensity = dm.densityDpi }
        Log.i(TAG, "Screen: $screenWidth x $screenHeight @ $screenDensity dpi"); if (screenWidth <= 0 || screenHeight <= 0) { screenWidth = 1080; screenHeight = 1920; screenDensity = DisplayMetrics.DENSITY_DEFAULT }
    }
    private fun setupMediaProjection() {
        if (mediaProjection != null) { Log.d(TAG, "MP já config."); return }
        if (initialResultCode == Activity.RESULT_CANCELED || initialResultData == null) { // <<< USA Activity
            Log.e(TAG, "MP sem dados válidos para iniciar.");
            stopSelf()
            return
        }
        val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try {
            mediaProjection = pm.getMediaProjection(initialResultCode, initialResultData!!)
            if (mediaProjection == null) { Log.e(TAG, "MP get null."); stopSelf(); return }
            mediaProjection?.registerCallback(object : MediaProjection.Callback() { override fun onStop() { Log.w(TAG, "MP parado externamente."); stopScreenCaptureInternal(); stopSelf() } }, mainHandler)
            Log.i(TAG, "MP config OK.");
            setupImageReader()
        } catch (e: Exception) { Log.e(TAG, "Erro config MP: ${e.message}"); mediaProjection = null; stopSelf() }
    }
    private fun setupImageReader() {
        if (imageReader != null) { Log.d(TAG, "IR já config."); return }; if (screenWidth <= 0 || screenHeight <= 0) { Log.e(TAG,"IR dim inválidas."); return }; imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2); Log.d(TAG, "IR config: ${screenWidth}x$screenHeight"); imageReader?.setOnImageAvailableListener({ r -> processingHandler.post { processAvailableImage(r) } }, processingHandler)
    }
    private fun startScreenCaptureInternal() {
        if (mediaProjection == null) { Log.e(TAG, "Falha start: MP nulo."); setupMediaProjection(); if(mediaProjection == null) return }; if (imageReader == null) { Log.e(TAG, "Falha start: IR nulo."); setupImageReader(); if(imageReader == null) return }; if (isCapturingActive.get()) { Log.d(TAG, "Start ignorado: já ativo."); return }; Log.i(TAG, "Iniciando VD...")
        try { virtualDisplay = mediaProjection!!.createVirtualDisplay("ScreenCapture", screenWidth, screenHeight, screenDensity, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader!!.surface, null, processingHandler); isCapturingActive.set(true); updateNotification("SmartDriver monitorando..."); Log.i(TAG, "Captura INICIADA.")
        } catch (e: SecurityException) { Log.e(TAG, "Erro segurança VD: ${e.message}"); isCapturingActive.set(false); stopSelf() } catch (e: Exception) { isCapturingActive.set(false); Log.e(TAG, "Erro criar VD: ${e.message}"); stopSelf() }
    }
    private fun stopScreenCaptureInternal() {
        if (!isCapturingActive.getAndSet(false)) { Log.d(TAG, "Stop ignorado: já inativo."); return }; Log.w(TAG, "Parando captura..."); try { virtualDisplay?.release() } catch (e: Exception) { Log.e(TAG, "Erro release VD: ${e.message}") } finally { virtualDisplay = null }; try { imageReader?.close() } catch (e: Exception) { Log.e(TAG, "Erro close IR: ${e.message}") } finally { imageReader = null }; try { mediaProjection?.stop() } catch (e: Exception) { Log.e(TAG, "Erro stop MP: ${e.message}") } finally { mediaProjection = null };
        initialResultCode = Activity.RESULT_CANCELED // <<< USA Activity
        initialResultData = null
        MediaProjectionData.clear()
        isProcessingImage.set(false); lastScreenHash = null; Log.i(TAG, "Captura PARADA."); updateNotification("SmartDriver inativo")
    }

    private fun captureAndProcessScreenshot() {
        val timeStartCapture = System.currentTimeMillis(); Log.d(TAG, "[TIME] captureAndProcessScreenshot solicitado at $timeStartCapture"); if (isProcessingImage.get()) { Log.d(TAG, "CAPTURE_NOW ignorado: Proc em andamento."); return }; processingHandler.post { val timeProcessingStart = System.currentTimeMillis(); Log.d(TAG, "[TIME] processAvailableImage (BG) iniciado at $timeProcessingStart (Atraso: ${timeProcessingStart-timeStartCapture}ms)"); processAvailableImage(imageReader) }
    }
    private fun processAvailableImage(reader: ImageReader?) {
        if (reader == null) { Log.w(TAG, "IR nulo."); return }; if (!isProcessingImage.compareAndSet(false, true)) { Log.d(TAG, "Proc pulado: isProcessingImage=true."); return }
        var image: Image? = null; var originalBitmap: Bitmap? = null; val processingStartTime = System.currentTimeMillis()
        try { val t0 = System.currentTimeMillis(); image = reader.acquireLatestImage(); val t1 = System.currentTimeMillis(); if(image != null) { Log.d(TAG, "[TIME] acquireLatest demorou ${t1 - t0}ms") } else { Log.w(TAG, "acquireLatest null (talvez já parado?)."); isProcessingImage.set(false); return }; val t2 = System.currentTimeMillis(); originalBitmap = imageToBitmap(image); val t3 = System.currentTimeMillis(); if(originalBitmap != null) { Log.d(TAG, "[TIME] imageToBitmap demorou ${t3 - t2}ms") } else { Log.e(TAG, "Falha imageToBitmap."); isProcessingImage.set(false); return }; val t4 = System.currentTimeMillis(); val hash = calculateImageHash(originalBitmap); if (t4 - lastHashTime > HASH_CACHE_DURATION_MS) { lastScreenHash = null }; if (hash == lastScreenHash) { Log.v(TAG, "Hash igual. Pulando."); originalBitmap.recycle(); isProcessingImage.set(false); return }; lastScreenHash = hash; lastHashTime = t4; Log.d(TAG, "Nova imagem (hash: $hash)."); if (shouldSaveScreenshots) { saveScreenshotToGallery(originalBitmap.copy(originalBitmap.config, true), "SCREEN") }; val t5 = System.currentTimeMillis(); val roi = imageAnalysisUtils.getRegionsOfInterest(originalBitmap.width, originalBitmap.height).firstOrNull(); var bitmapToAnalyze: Bitmap? = null; var regionTag = "UNKNOWN"; if (roi == null || roi.isEmpty) { Log.w(TAG, "ROI inválida ou vazia, usando tela inteira."); bitmapToAnalyze = originalBitmap; regionTag = "FULL_ROI_FAIL" } else { bitmapToAnalyze = imageAnalysisUtils.cropToRegion(originalBitmap, roi); val t6 = System.currentTimeMillis(); Log.d(TAG, "[TIME] cropToRegion demorou ${t6 - t5}ms"); if (bitmapToAnalyze != null) { Log.d(TAG, "Processando ROI: ${roi.flattenToString()}"); originalBitmap.recycle(); regionTag = "ROI" } else { Log.e(TAG, "Falha crop. Usando full."); bitmapToAnalyze = originalBitmap; regionTag = "FULL_CROP_FAIL" } }; if (bitmapToAnalyze != null) { processBitmapRegion(bitmapToAnalyze, screenWidth, screenHeight, regionTag) } else { Log.e(TAG,"bitmapToAnalyze nulo."); isProcessingImage.set(false); if (originalBitmap != null && !originalBitmap.isRecycled) { originalBitmap.recycle() } }
        } catch (e: Exception) { Log.e(TAG, "Erro processAvailableImage: ${e.message}", e); isProcessingImage.set(false); if (originalBitmap != null && !originalBitmap.isRecycled) { originalBitmap.recycle() } }
        finally { image?.close(); val procTime = System.currentTimeMillis() - processingStartTime; Log.d(TAG,"[TIME] processAvailableImage (total BG) demorou ${procTime}ms") }
    }
    private fun imageToBitmap(image: Image): Bitmap? { if(image.format != PixelFormat.RGBA_8888){Log.w(TAG,"Formato inesperado: ${image.format}"); return null}; val p=image.planes;val b=p[0].buffer;val ps=p[0].pixelStride;val rs=p[0].rowStride;val rp=rs-ps*image.width; try{val bmp=Bitmap.createBitmap(image.width+rp/ps,image.height,Bitmap.Config.ARGB_8888);bmp.copyPixelsFromBuffer(b);if(rp==0)return bmp;else{val cb=Bitmap.createBitmap(bmp,0,0,image.width,image.height);bmp.recycle();return cb}}catch(e:Exception){Log.e(TAG,"Erro imageToBitmap: ${e.message}");return null} }
    private fun calculateImageHash(bitmap: Bitmap): Int { val s=64; try{val sb=Bitmap.createScaledBitmap(bitmap,s,s,true);val p=IntArray(s*s);sb.getPixels(p,0,s,0,0,s,s);sb.recycle();return p.contentHashCode()}catch(e:Exception){return bitmap.hashCode()} }

    private fun processBitmapRegion(bitmapToProcess: Bitmap, originalWidth: Int, originalHeight: Int, regionTag: String) {
        var bitmapForOcr: Bitmap? = null
        val scaleFactor = OCR_IMAGE_SCALE_FACTOR
        val newWidth = (bitmapToProcess.width * scaleFactor).toInt()
        val newHeight = (bitmapToProcess.height * scaleFactor).toInt()
        val timeResizeStart = System.currentTimeMillis()

        try {
            if (newWidth > 0 && newHeight > 0) {
                bitmapForOcr = Bitmap.createScaledBitmap(bitmapToProcess, newWidth, newHeight, true)
                val timeResizeEnd = System.currentTimeMillis()
                Log.d(TAG, "[TIME] Redimensionamento para ${newWidth}x$newHeight demorou ${timeResizeEnd - timeResizeStart}ms")
                if (!bitmapToProcess.isRecycled) { bitmapToProcess.recycle(); Log.d(TAG, "Bitmap original ($regionTag) reciclado pós-resize.") }
            } else {
                Log.w(TAG, "Resize inválido ($newWidth x $newHeight), usando original ($regionTag)."); bitmapForOcr = bitmapToProcess
            }

            if (bitmapForOcr == null) { Log.e(TAG, "bitmapForOcr nulo ($regionTag)."); isProcessingImage.set(false); return }

            val timeOcrStart = System.currentTimeMillis()
            Log.d(TAG, "[TIME] Iniciando OCR ($regionTag - ${bitmapForOcr.width}x${bitmapForOcr.height}) at $timeOcrStart")
            val inputImage = InputImage.fromBitmap(bitmapForOcr, 0)
            val finalBitmapForOcr = bitmapForOcr

            textRecognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    val timeOcrEnd = System.currentTimeMillis(); Log.i(TAG, "[TIME] OCR ($regionTag) SUCESSO. Demorou ${timeOcrEnd - timeOcrStart}ms")
                    val extractedText = visionText.text
                    if (extractedText.isNotBlank()) {
                        Log.d(TAG,"Texto extraído ($regionTag), ${extractedText.length} chars.")
                        val currentTime = System.currentTimeMillis()
                        val timeSinceLastOffer = currentTime - lastOfferDetectedTime

                        val timeAnalysisStart = System.currentTimeMillis()
                        val offerData = imageAnalysisUtils.analyzeTextForOffer(extractedText)
                        val timeAnalysisEnd = System.currentTimeMillis(); Log.d(TAG, "[TIME] Análise texto demorou ${timeAnalysisEnd-timeAnalysisStart}ms")

                        if (offerData != null && offerData.isValid()) {
                            val offerSignature = createOfferSignature(offerData)
                            if (timeSinceLastOffer >= OFFER_PROCESSING_LOCK_PERIOD_MS || offerSignature != lastDetectedOfferSignature) {
                                Log.i(TAG, "!!!! OFERTA VÁLIDA DETECTADA ($regionTag) !!!! Processando...")
                                Log.i(TAG, "   Valor: ${offerData.value}€, Dist(P): ${offerData.pickupDistance}, Dist(T): ${offerData.tripDistance}, Tempo(P): ${offerData.pickupDuration}, Tempo(T): ${offerData.tripDuration}")
                                lastOfferDetectedTime = currentTime; lastDetectedOfferSignature = offerSignature
                                val t0 = System.currentTimeMillis(); mainHandler.post { Log.d(TAG, "[TIME] Enviando para OfferManager at $t0"); OfferManager.getInstance(applicationContext).processOffer(offerData) }
                                if (shouldSaveScreenshots) { saveScreenshotToGallery(finalBitmapForOcr.copy(finalBitmapForOcr.config, false), "OFERTA_${regionTag}_OCR") }
                            } else {
                                Log.d(TAG, "Oferta ignorada ($regionTag): Bloqueio ativo (${timeSinceLastOffer}ms < ${OFFER_PROCESSING_LOCK_PERIOD_MS}ms) OU assinatura idêntica.")
                            }
                        } else { Log.d(TAG, "Nenhuma oferta válida encontrada no texto ($regionTag).") }
                    } else { Log.d(TAG, "Nenhum texto extraído da região ($regionTag).") }
                }
                .addOnFailureListener { e -> val t = System.currentTimeMillis(); Log.e(TAG, "[TIME] Falha OCR ($regionTag) após ${t - timeOcrStart}ms: ${e.message}") }
                .addOnCompleteListener {
                    if (!finalBitmapForOcr.isRecycled) { finalBitmapForOcr.recycle(); Log.d(TAG,"BitmapForOcr ($regionTag) reciclado no complete.") } else { Log.d(TAG,"BitmapForOcr ($regionTag) já reciclado antes do complete.")}
                    isProcessingImage.compareAndSet(true, false); Log.d(TAG,"[TIME] Processamento OCR ($regionTag) concluído, isProcessingImage=false")
                }

        } catch (e: Exception) {
            Log.e(TAG, "Erro processBitmapRegion ($regionTag): ${e.message}");
            if (bitmapForOcr != null && !bitmapForOcr.isRecycled) bitmapForOcr.recycle()
            if (bitmapToProcess != bitmapForOcr && !bitmapToProcess.isRecycled) bitmapToProcess.recycle()
            isProcessingImage.set(false)
        }
    }

    private fun createOfferSignature(offerData: OfferData): String { val v=offerData.value.replace(",","."); val pd=offerData.pickupDistance.ifBlank{"0"}; val td=offerData.tripDistance.ifBlank{"0"}; val pt=offerData.pickupDuration.ifBlank{"0"}; val tt=offerData.tripDuration.ifBlank{"0"}; return "v:$v|pd:$pd|td:$td|pt:$pt|tt:$tt" }
    private fun saveScreenshotToGallery(bitmap: Bitmap, prefix: String) { if(!shouldSaveScreenshots){bitmap.recycle();return};processingHandler.post{try{val tS=SimpleDateFormat("yyMMdd_HHmmss_SSS",Locale.getDefault()).format(Date());val fN="SmartDriver_${prefix}_${tS}_${screenshotCounter++}.jpg";val cV=ContentValues().apply{put(MediaStore.Images.Media.DISPLAY_NAME,fN);put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg");put(MediaStore.Images.Media.DATE_ADDED,System.currentTimeMillis()/1000);put(MediaStore.Images.Media.DATE_TAKEN,System.currentTimeMillis());if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.Q){put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/SmartDriverDebug");put(MediaStore.Images.Media.IS_PENDING,1)}};val uri=contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,cV);if(uri==null){Log.e(TAG,"URI null save.");bitmap.recycle();return@post};contentResolver.openOutputStream(uri)?.use{it.run{bitmap.compress(Bitmap.CompressFormat.JPEG,90,this);Log.d(TAG,"Img salva: $fN")}}?:Log.e(TAG,"OutputStream null: $uri");if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.Q){cV.clear();cV.put(MediaStore.Images.Media.IS_PENDING,0);try{contentResolver.update(uri,cV,null,null)}catch(e:Exception){Log.e(TAG,"Erro IS_PENDING 0: ${e.message}")}}}catch(e:Exception){Log.e(TAG,"Erro saveScreenshot: ${e.message}",e)}finally{if(!bitmap.isRecycled)bitmap.recycle()}}}
    private fun createNotification(contentText: String): Notification { createNotificationChannel(); return NotificationCompat.Builder(this, CHANNEL_ID).setContentTitle("SmartDriver").setContentText(contentText).setSmallIcon(R.drawable.ic_stat_name).setOngoing(true).setCategory(NotificationCompat.CATEGORY_SERVICE).setPriority(NotificationCompat.PRIORITY_LOW).build() }
    private fun updateNotification(contentText: String) { val n=createNotification(contentText); val nm=getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager; nm.notify(NOTIFICATION_ID, n) }
    private fun createNotificationChannel() { if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.O){val c=NotificationChannel(CHANNEL_ID,CHANNEL_NAME,NotificationManager.IMPORTANCE_LOW).apply{description="Serviço SmartDriver";enableLights(false);enableVibration(false);setShowBadge(false)}; val nm=getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager;nm.createNotificationChannel(c);Log.d(TAG,"Canal Notif OK.")}}
    override fun onBind(intent: Intent?): IBinder? { return null }
    override fun onDestroy() {
        Log.w(TAG, "Serviço Captura DESTRUÍDO");
        isRunning.set(false)
        stopScreenCaptureInternal();
        if(::processingThread.isInitialized){processingThread.quitSafely()};
        super.onDestroy()
    }
}