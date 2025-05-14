package com.example.smartdriver.overlay // Ou com.example.smartdriver.services, conforme a tua estrutura

// Importações Essenciais Corrigidas/Adicionadas
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Resources
import android.graphics.PixelFormat
import android.os.* // Para Parcelable, Build, Handler, Looper, IBinder
import android.util.Log
import android.view.*
import android.widget.ImageButton
import android.widget.ImageView.ScaleType
import androidx.core.app.NotificationCompat
import com.example.smartdriver.R
import com.example.smartdriver.SettingsActivity
import com.example.smartdriver.ScreenCaptureService
import com.example.smartdriver.MediaProjectionData
import com.example.smartdriver.MainActivity
import com.example.smartdriver.utils.OfferData
import com.example.smartdriver.utils.EvaluationResult
import com.example.smartdriver.utils.IndividualRating
import com.example.smartdriver.utils.TripHistoryEntry
import com.example.smartdriver.utils.BorderRating
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.text.DecimalFormat
import java.text.NumberFormat // Para formatação de moeda
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max

class OverlayService : Service() {

    companion object {
        internal const val TAG = "OverlayService"
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "overlay_service_channel"
        private const val CHANNEL_NAME = "Overlay Service"
        const val ACTION_SHOW_OVERLAY = "com.example.smartdriver.overlay.SHOW_OVERLAY"
        const val ACTION_HIDE_OVERLAY = "com.example.smartdriver.overlay.HIDE_OVERLAY"
        const val ACTION_DISMISS_MAIN_OVERLAY_ONLY = "com.example.smartdriver.overlay.DISMISS_MAIN_ONLY"
        const val ACTION_UPDATE_SETTINGS = "com.example.smartdriver.overlay.UPDATE_SETTINGS"
        const val ACTION_START_TRACKING = "com.example.smartdriver.overlay.START_TRACKING"
        const val ACTION_STOP_TRACKING = "com.example.smartdriver.overlay.STOP_TRACKING"
        const val ACTION_SHOW_QUICK_MENU = "com.example.smartdriver.overlay.SHOW_QUICK_MENU"
        const val ACTION_DISMISS_MENU = "com.example.smartdriver.overlay.DISMISS_MENU"
        const val ACTION_REQUEST_SHUTDOWN = "com.example.smartdriver.overlay.REQUEST_SHUTDOWN"
        const val ACTION_TOGGLE_SHIFT_STATE = "com.example.smartdriver.overlay.TOGGLE_SHIFT_STATE"
        const val ACTION_END_SHIFT = "com.example.smartdriver.overlay.END_SHIFT"
        const val EXTRA_EVALUATION_RESULT = "evaluation_result"
        const val EXTRA_OFFER_DATA = "offer_data"
        const val EXTRA_FONT_SIZE = "font_size"
        const val EXTRA_TRANSPARENCY = "transparency"
        private const val TRACKING_UPDATE_INTERVAL_MS = 1000L
        private const val MIN_TRACKING_TIME_SEC = 1L
        const val HISTORY_PREFS_NAME = "SmartDriverHistoryPrefs"
        const val KEY_TRIP_HISTORY = "trip_history_list_json"
        const val SHIFT_STATE_PREFS_NAME = "SmartDriverShiftStatePrefs"
        private const val KEY_SHIFT_ACTIVE = "shift_active"
        private const val KEY_SHIFT_PAUSED = "shift_paused"
        private const val KEY_SHIFT_START_TIME = "shift_start_time"
        private const val KEY_SHIFT_LAST_PAUSE_OR_RESUME_TIME = "shift_last_pause_resume_time"
        private const val KEY_SHIFT_ACCUMULATED_WORKED_TIME = "shift_accumulated_worked_time"
        private const val KEY_SHIFT_TOTAL_EARNINGS = "shift_total_earnings" // Base para média
        @JvmStatic val isRunning = AtomicBoolean(false)
    }

    private var windowManager: WindowManager? = null
    private var mainOverlayView: OverlayView? = null
    private var trackingOverlayView: TrackingOverlayView? = null
    private var quickMenuView: MenuView? = null
    private var floatingIconView: ImageButton? = null
    private lateinit var floatingIconLayoutParams: WindowManager.LayoutParams
    private lateinit var mainLayoutParams: WindowManager.LayoutParams
    private lateinit var trackingLayoutParams: WindowManager.LayoutParams
    private lateinit var menuLayoutParams: WindowManager.LayoutParams
    private var isMainOverlayAdded = false
    private var isTrackingOverlayAdded = false
    private var isFloatingIconAdded = false
    private var isQuickMenuAdded = false

    private var isCurrentlyTracking = false
    private var trackingStartTimeMs: Long = 0L
    private var trackedOfferData: OfferData? = null
    private var trackedInitialVph: Double? = null
    private var trackedInitialVpk: Double? = null
    private var trackedOfferValue: Double = 0.0
    private var trackedInitialKmRating: IndividualRating = IndividualRating.UNKNOWN
    private var trackedCombinedBorderRating: BorderRating = BorderRating.GRAY
    private val trackingUpdateHandler = Handler(Looper.getMainLooper())
    private lateinit var trackingUpdateRunnable: Runnable

    private var isShiftActive = false
    private var isShiftPaused = false
    private var shiftStartTimeMillis = 0L
    private var shiftLastPauseOrResumeTimeMillis = 0L
    private var shiftAccumulatedWorkedTimeMillis = 0L
    private var shiftTotalEarnings = 0.0 // Soma real das ofertas para calcular a média
    private val shiftTimerHandler = Handler(Looper.getMainLooper())
    private var shiftTimerRunnable: Runnable? = null
    private lateinit var shiftPrefs: SharedPreferences

    private var goodHourThreshold: Double = 15.0
    private var poorHourThreshold: Double = 8.0
    private val gson = Gson()
    private lateinit var historyPrefs: SharedPreferences
    private var touchSlop: Int = 0
    // private val earningsDecimalFormat = DecimalFormat("0.00") // Substituído por currencyFormatter
    private val averageDecimalFormat = DecimalFormat("0.0")
    private val currencyFormatter: NumberFormat = NumberFormat.getCurrencyInstance(Locale("pt", "PT")).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
    }


    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Serviço Overlay CRIADO")
        isRunning.set(true)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        historyPrefs = getSharedPreferences(HISTORY_PREFS_NAME, Context.MODE_PRIVATE)
        shiftPrefs = getSharedPreferences(SHIFT_STATE_PREFS_NAME, Context.MODE_PRIVATE)
        touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        loadTrackingThresholds()
        loadShiftState()
        initializeMainLayoutParams()
        initializeTrackingLayoutParams()
        initializeMenuLayoutParams()
        initializeFloatingIconLayoutParams()
        setupTrackingRunnable()
        setupShiftTimerRunnable()
        startForeground(NOTIFICATION_ID, createNotification("Overlay pronto"))
        addFloatingIconOverlay()
        if (isShiftActive && !isShiftPaused) {
            startShiftTimer()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: Action=${intent?.action}")
        when (intent?.action) {
            ACTION_SHOW_OVERLAY -> handleShowOverlay(intent)
            ACTION_HIDE_OVERLAY -> handleHideOverlay()
            ACTION_DISMISS_MAIN_OVERLAY_ONLY -> handleDismissMainOverlayOnly()
            ACTION_START_TRACKING -> handleStartTracking(intent)
            ACTION_STOP_TRACKING -> handleStopTracking()
            ACTION_UPDATE_SETTINGS -> handleUpdateSettings(intent)
            ACTION_SHOW_QUICK_MENU -> handleShowQuickMenu()
            ACTION_DISMISS_MENU -> handleDismissMenu()
            ACTION_REQUEST_SHUTDOWN -> handleShutdownRequest()
            ACTION_TOGGLE_SHIFT_STATE -> handleToggleShiftState()
            ACTION_END_SHIFT -> handleEndShift()
            else -> Log.w(TAG, "Ação desconhecida/nula: ${intent?.action}")
        }
        return START_REDELIVER_INTENT
    }

    // --- Implementação OBRIGATÓRIA de onBind ---
    override fun onBind(intent: Intent?): IBinder? {
        return null // Não permite vinculação
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.w(TAG, "====== Serviço Overlay DESTRUÍDO ======")
        isRunning.set(false); stopTrackingTimer(); stopShiftTimer()
        if (isShiftActive) {
            val cT=System.currentTimeMillis(); if (!isShiftPaused && shiftLastPauseOrResumeTimeMillis > 0) { val wMS=cT-shiftLastPauseOrResumeTimeMillis; if (wMS > 0) { shiftAccumulatedWorkedTimeMillis += wMS } }; shiftLastPauseOrResumeTimeMillis = cT; isShiftPaused = true; saveShiftState()
            Log.i(TAG,"Estado final turno (pausado) salvo onDestroy.")
        } else {
            if (shiftStartTimeMillis == 0L) { // Garante que só limpa se o turno realmente terminou
                shiftPrefs.edit().clear().apply()
                Log.i(TAG,"Preferências de turno limpas ao destruir (turno não ativo e terminado).")
            }
        }
        hideMainOverlay(); hideTrackingOverlay(); removeQuickMenuOverlay(); removeFloatingIconOverlay()
        mainOverlayView=null; trackingOverlayView=null; windowManager=null; quickMenuView=null; floatingIconView=null; shiftTimerRunnable=null
        try{val nm=getSystemService(Context.NOTIFICATION_SERVICE)as NotificationManager;nm.cancel(NOTIFICATION_ID)}catch(e:Exception){}
    }

    // --- Funções de Inicialização ---
    private fun loadTrackingThresholds() { try{goodHourThreshold=SettingsActivity.getGoodHourThreshold(this);poorHourThreshold=SettingsActivity.getPoorHourThreshold(this)}catch(e:Exception){Log.w(TAG,"Erro carregar limiares.",e);goodHourThreshold=15.0;poorHourThreshold=8.0} }
    private fun initializeMainLayoutParams() { val oT=if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.O)WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE; val f=WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS; mainLayoutParams=WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.WRAP_CONTENT,oT,f,PixelFormat.TRANSLUCENT).apply{gravity=Gravity.TOP or Gravity.CENTER_HORIZONTAL;y=(50*resources.displayMetrics.density).toInt()} }
    private fun initializeTrackingLayoutParams() { val oT=if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.O)WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE; val f=WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS; trackingLayoutParams=WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.WRAP_CONTENT,oT,f,PixelFormat.TRANSLUCENT).apply{gravity=Gravity.TOP or Gravity.START;x=(10*resources.displayMetrics.density).toInt();y=(80*resources.displayMetrics.density).toInt()} }
    private fun initializeMenuLayoutParams() { val oT=if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.O)WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE; val f=WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL; menuLayoutParams=WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.WRAP_CONTENT,oT,f,PixelFormat.TRANSLUCENT).apply{gravity=Gravity.TOP or Gravity.START;x=(10*resources.displayMetrics.density).toInt();y=(80*resources.displayMetrics.density).toInt()} }
    private fun initializeFloatingIconLayoutParams() { val oT=if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.O)WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE; val f=WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN; val iS=(60*resources.displayMetrics.density).toInt(); floatingIconLayoutParams=WindowManager.LayoutParams(iS,iS,oT,f,PixelFormat.TRANSLUCENT).apply{gravity=Gravity.TOP or Gravity.START;x=(10*resources.displayMetrics.density).toInt();y=(10*resources.displayMetrics.density).toInt()} }

    // --- Runnables ---
    private fun setupTrackingRunnable() {
        trackingUpdateRunnable = object : Runnable {
            override fun run() {
                if (isCurrentlyTracking && trackingOverlayView != null) {
                    val elapsedMillis = System.currentTimeMillis() - trackingStartTimeMs
                    val elapsedSeconds = max(MIN_TRACKING_TIME_SEC, elapsedMillis / 1000L)
                    var currentVph: Double? = null
                    if (trackedOfferValue > 0) {
                        val elapsedHours = elapsedSeconds / 3600.0
                        if (elapsedHours > 0) {
                            val calc = trackedOfferValue / elapsedHours
                            if (calc.isFinite()) currentVph = calc
                        }
                    }
                    val currentHourRating = when {
                        currentVph == null -> IndividualRating.UNKNOWN
                        currentVph >= goodHourThreshold -> IndividualRating.GOOD
                        currentVph <= poorHourThreshold -> IndividualRating.POOR
                        else -> IndividualRating.MEDIUM
                    }
                    trackingOverlayView?.updateRealTimeData(currentVph, currentHourRating, elapsedSeconds)
                    trackingUpdateHandler.postDelayed(this, TRACKING_UPDATE_INTERVAL_MS)
                }
            }
        }
    }

    private fun setupShiftTimerRunnable() {
        shiftTimerRunnable = object : Runnable {
            override fun run() {
                if (isShiftActive && !isShiftPaused && shiftTimerRunnable != null) {
                    val workedTimeMillis = calculateCurrentWorkedTimeMillis()
                    val formattedTime = formatDuration(workedTimeMillis)

                    // 1. Obter a média €/h atual (como valor numérico)
                    val averagePerHourNumeric = getNumericShiftAveragePerHourValue(workedTimeMillis) // Média REAL

                    // 2. Calcular ganhos ESTIMADOS com base na média e tempo
                    val estimatedEarningsValue = if (averagePerHourNumeric != null && workedTimeMillis > 0 && averagePerHourNumeric.isFinite()) {
                        val workedHours = workedTimeMillis / 3600000.0 // milissegundos para horas
                        averagePerHourNumeric * workedHours
                    } else {
                        0.0
                    }
                    val formattedEstimatedEarnings = currencyFormatter.format(estimatedEarningsValue)

                    // 3. Formata a média €/h REAL para exibição
                    val averagePerHourStringForDisplay = calculateCurrentShiftAveragePerHourString(averagePerHourNumeric)


                    quickMenuView?.updateShiftTimer(formattedTime)
                    quickMenuView?.updateShiftEarnings(formattedEstimatedEarnings) // Passa a projeção
                    quickMenuView?.updateShiftAverage(averagePerHourStringForDisplay) // Passa a média real formatada

                    shiftTimerHandler.postDelayed(this, 1000L)
                }
            }
        }
    }

    // --- Notificações ---
    private fun createNotification(contentText: String, isTrackingOrActive: Boolean = false): Notification { createNotificationChannel(); val sI=try{if(isTrackingOrActive)R.drawable.ic_stat_tracking else R.mipmap.ic_launcher}catch(e:Resources.NotFoundException){R.mipmap.ic_launcher}; return NotificationCompat.Builder(this,CHANNEL_ID).setContentTitle("SmartDriver").setContentText(contentText).setSmallIcon(sI).setOngoing(true).setCategory(NotificationCompat.CATEGORY_SERVICE).setPriority(NotificationCompat.PRIORITY_LOW).build() }
    private fun updateNotification(contentText: String, isTrackingOrActive: Boolean = false) { val nm=getSystemService(Context.NOTIFICATION_SERVICE)as NotificationManager; try{nm.notify(NOTIFICATION_ID,createNotification(contentText,isTrackingOrActive))}catch(e:Exception){Log.e(TAG,"Erro atualizar notificação.",e)} }
    private fun createNotificationChannel() { if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.O){val ch=NotificationChannel(CHANNEL_ID,CHANNEL_NAME,NotificationManager.IMPORTANCE_LOW).apply{description="Notificação Serviço SmartDriver";enableLights(false);enableVibration(false);setShowBadge(false)}; val nm=getSystemService(Context.NOTIFICATION_SERVICE)as NotificationManager; try{nm.createNotificationChannel(ch)}catch(e:Exception){Log.e(TAG,"Erro criar canal notificação.",e)}} }

    // --- Handlers das Ações ---
    private fun handleShowOverlay(intent: Intent?) { val eR=getParcelableExtraCompat(intent,EXTRA_EVALUATION_RESULT,EvaluationResult::class.java); val oD=getParcelableExtraCompat(intent,EXTRA_OFFER_DATA,OfferData::class.java); if(eR!=null&&oD!=null){showMainOverlay(eR,oD);updateShiftNotification()}else{hideMainOverlay()} }
    private fun handleHideOverlay() { if(isCurrentlyTracking){stopTrackingAndSaveToHistory()}; hideMainOverlay();hideTrackingOverlay();removeQuickMenuOverlay();updateShiftNotification() }
    private fun handleDismissMainOverlayOnly() { hideMainOverlay() }
    private fun handleStartTracking(intent: Intent?) { val oD=getParcelableExtraCompat(intent,EXTRA_OFFER_DATA,OfferData::class.java); val iE=getParcelableExtraCompat(intent,EXTRA_EVALUATION_RESULT,EvaluationResult::class.java); if(oD!=null&&iE!=null&&!isCurrentlyTracking){hideMainOverlay();removeQuickMenuOverlay();isCurrentlyTracking=true;trackingStartTimeMs=System.currentTimeMillis();trackedOfferData=oD;trackedOfferValue=try{oD.value.replace(",",".").toDouble()}catch(e:NumberFormatException){0.0};trackedInitialVph=oD.calculateValuePerHour();trackedInitialVpk=oD.calculateProfitability();trackedInitialKmRating=iE.kmRating;trackedCombinedBorderRating=iE.combinedBorderRating;loadTrackingThresholds();val iDt=oD.calculateTotalDistance()?.takeIf{it>0};val iDu=oD.calculateTotalTimeMinutes()?.takeIf{it>0};val oV=oD.value;showTrackingOverlay(trackedInitialVpk,iDt,iDu,oV,trackedInitialKmRating,trackedCombinedBorderRating);trackingUpdateHandler.removeCallbacks(trackingUpdateRunnable);trackingUpdateHandler.post(trackingUpdateRunnable);updateShiftNotification()}}
    private fun handleStopTracking() { if(isCurrentlyTracking){stopTrackingAndSaveToHistory()} }
    private fun handleUpdateSettings(intent: Intent?) { loadTrackingThresholds(); val nFS=intent?.getIntExtra(EXTRA_FONT_SIZE,SettingsActivity.getFontSize(this))?:SettingsActivity.getFontSize(this); val nT=intent?.getIntExtra(EXTRA_TRANSPARENCY,SettingsActivity.getTransparency(this))?:SettingsActivity.getTransparency(this); applyAppearanceSettings(nFS,nT); updateLayouts() }
    private fun handleShowQuickMenu() { addQuickMenuOverlay() }
    private fun handleDismissMenu() { removeQuickMenuOverlay() }
    private fun handleShutdownRequest() { handleEndShift(false); removeQuickMenuOverlay();stopTrackingAndSaveToHistory();hideMainOverlay();hideTrackingOverlay();removeFloatingIconOverlay(); try{stopService(Intent(this,ScreenCaptureService::class.java))}catch(e:Exception){}; MediaProjectionData.clear(); val sI=Intent(MainActivity.ACTION_SHUTDOWN_APP); try{sendBroadcast(sI)}catch(e:Exception){}; stopSelf() }

    // --- Helper getParcelableExtraCompat ---
    // (Já incluído no seu código fornecido)

    // --- Gestão das Views ---
    private fun showMainOverlay(eR: EvaluationResult, oD: OfferData) { if(windowManager==null){return}; if(mainOverlayView==null){mainOverlayView=OverlayView(this);applyAppearanceSettingsToView(mainOverlayView)}; mainOverlayView?.updateState(eR,oD); try{if(!isMainOverlayAdded){windowManager?.addView(mainOverlayView,mainLayoutParams);isMainOverlayAdded=true}else{windowManager?.updateViewLayout(mainOverlayView,mainLayoutParams)}}catch(e:Exception){isMainOverlayAdded=false;mainOverlayView=null} }
    private fun hideMainOverlay() { if(isMainOverlayAdded&&mainOverlayView!=null&&windowManager!=null){try{windowManager?.removeViewImmediate(mainOverlayView)}catch(e:Exception){}finally{isMainOverlayAdded=false;mainOverlayView=null}}}
    private fun showTrackingOverlay(iV:Double?,iD:Double?,iDu:Int?,oV:String?,iKR:IndividualRating,cB:BorderRating) { val cWM=windowManager;if(cWM==null){return}; if(trackingOverlayView==null){trackingOverlayView=TrackingOverlayView(this,cWM,trackingLayoutParams);applyAppearanceSettingsToView(trackingOverlayView)}; trackingOverlayView?.updateInitialData(iV,iD,iDu,oV,iKR,cB); try{if(!isTrackingOverlayAdded&&trackingOverlayView!=null){cWM.addView(trackingOverlayView,trackingLayoutParams);isTrackingOverlayAdded=true}else if(isTrackingOverlayAdded&&trackingOverlayView!=null){cWM.updateViewLayout(trackingOverlayView,trackingLayoutParams)}}catch(e:Exception){isTrackingOverlayAdded=false;trackingOverlayView=null} }
    private fun hideTrackingOverlay() { if(isTrackingOverlayAdded&&trackingOverlayView!=null&&windowManager!=null){try{windowManager?.removeViewImmediate(trackingOverlayView)}catch(e:Exception){}finally{isTrackingOverlayAdded=false;trackingOverlayView=null}}}
    @SuppressLint("ClickableViewAccessibility") private fun addFloatingIconOverlay() { if(windowManager==null||isFloatingIconAdded){return}; if(floatingIconView==null){floatingIconView=ImageButton(this).apply{setImageResource(R.drawable.smartdriver);setBackgroundResource(R.drawable.fab_background);scaleType=ScaleType.CENTER_INSIDE;setOnTouchListener(createFloatingIconTouchListener())}}; try{windowManager?.addView(floatingIconView,floatingIconLayoutParams);isFloatingIconAdded=true}catch(e:Exception){isFloatingIconAdded=false;floatingIconView=null} }
    private fun removeFloatingIconOverlay() { if(isFloatingIconAdded&&floatingIconView!=null&&windowManager!=null){try{windowManager?.removeViewImmediate(floatingIconView)}catch(e:Exception){}finally{isFloatingIconAdded=false;floatingIconView=null}}}
    private fun addQuickMenuOverlay() { if(windowManager==null||isQuickMenuAdded){return}; if(quickMenuView==null){quickMenuView=MenuView(this);updateMenuViewShiftUI()}; try{menuLayoutParams.x=floatingIconLayoutParams.x;menuLayoutParams.y=floatingIconLayoutParams.y+floatingIconLayoutParams.height+(5*resources.displayMetrics.density).toInt();windowManager?.addView(quickMenuView,menuLayoutParams);isQuickMenuAdded=true}catch(e:Exception){isQuickMenuAdded=false;quickMenuView=null} }
    private fun removeQuickMenuOverlay() { if(isQuickMenuAdded&&quickMenuView!=null&&windowManager!=null){try{windowManager?.removeViewImmediate(quickMenuView)}catch(e:Exception){}finally{isQuickMenuAdded=false;quickMenuView=null}}}
    @SuppressLint("ClickableViewAccessibility") private fun createFloatingIconTouchListener():View.OnTouchListener{ var iX=0;var iY=0;var iTX=0f;var iTY=0f;var sT=0L;var isD=false; return View.OnTouchListener{v,e->when(e.action){MotionEvent.ACTION_DOWN->{iX=floatingIconLayoutParams.x;iY=floatingIconLayoutParams.y;iTX=e.rawX;iTY=e.rawY;sT=System.currentTimeMillis();isD=false;true}MotionEvent.ACTION_MOVE->{val dX=abs(e.rawX-iTX);val dY=abs(e.rawY-iTY);if(dX>touchSlop||dY>touchSlop){isD=true};if(isD){val nX=iX+(e.rawX-iTX).toInt();val nY=iY+(e.rawY-iTY).toInt();val sW=Resources.getSystem().displayMetrics.widthPixels;val sH=Resources.getSystem().displayMetrics.heightPixels;val iW=v.width;val iH=v.height;floatingIconLayoutParams.x=nX.coerceIn(0,sW-iW);floatingIconLayoutParams.y=nY.coerceIn(0,sH-iH);try{if(isFloatingIconAdded&&floatingIconView!=null&&windowManager!=null){windowManager?.updateViewLayout(floatingIconView,floatingIconLayoutParams)}}catch(e:Exception){}};true}MotionEvent.ACTION_UP->{val dur=System.currentTimeMillis()-sT;if(!isD&&dur<ViewConfiguration.getTapTimeout()){if(!isQuickMenuAdded){handleShowQuickMenu()}else{handleDismissMenu()};v.performClick()}else{if(isQuickMenuAdded&&quickMenuView!=null&&windowManager!=null){try{menuLayoutParams.x=floatingIconLayoutParams.x;menuLayoutParams.y=floatingIconLayoutParams.y+floatingIconLayoutParams.height+(5*resources.displayMetrics.density).toInt();windowManager?.updateViewLayout(quickMenuView,menuLayoutParams)}catch(e:Exception){}}};isD=false;true}else->false}}}

    // --- Tracking e Histórico ---
    private fun stopTrackingTimer(){ trackingUpdateHandler.removeCallbacks(trackingUpdateRunnable); }
    private fun stopTrackingAndSaveToHistory(){ if(!isCurrentlyTracking){return}; val eT=System.currentTimeMillis(); stopTrackingTimer(); val fEM=eT-trackingStartTimeMs; val fES=max(MIN_TRACKING_TIME_SEC,fEM/1000L); var fV:Double?=null; if(trackedOfferValue>0){val fH=fES/3600.0;if(fH>0){val c=trackedOfferValue/fH;if(c.isFinite())fV=c}}; val finalVphFormatted = fV?.let { averageDecimalFormat.format(it) } ?: "--"; Log.i(TAG,"Dados Finais: Dur=${fES}s, €/h=$finalVphFormatted");val entry=TripHistoryEntry(startTimeMillis=trackingStartTimeMs,endTimeMillis=eT,durationSeconds=fES,offerValue=trackedOfferValue.takeIf{it>0},initialVph=trackedInitialVph,finalVph=fV,initialVpk=trackedInitialVpk,initialDistanceKm=trackedOfferData?.calculateTotalDistance()?.takeIf{it>0},initialDurationMinutes=trackedOfferData?.calculateTotalTimeMinutes()?.takeIf{it>0},serviceType=trackedOfferData?.serviceType?.takeIf{it.isNotEmpty()},originalBorderRating=this.trackedCombinedBorderRating); saveHistoryEntry(entry); if(isShiftActive){val oV=entry.offerValue?:0.0;if(oV>0){shiftTotalEarnings+=oV; Log.i(TAG,"Ganhos REAIS turno: +${currencyFormatter.format(oV)}. Total REAL: ${currencyFormatter.format(shiftTotalEarnings)}"); saveShiftState();updateMenuViewShiftUI()}}; isCurrentlyTracking=false;trackingStartTimeMs=0L;trackedOfferData=null;trackedOfferValue=0.0;trackedInitialVph=null;trackedInitialVpk=null;trackedInitialKmRating=IndividualRating.UNKNOWN;trackedCombinedBorderRating=BorderRating.GRAY; hideTrackingOverlay(); updateShiftNotification() }
    private fun saveHistoryEntry(nE:TripHistoryEntry){ try{val nJ=gson.toJson(nE);val cJ=historyPrefs.getString(KEY_TRIP_HISTORY,"[]");val lT=object:TypeToken<MutableList<String>>(){}.type;val l:MutableList<String> = try{gson.fromJson(cJ,lT)?:mutableListOf()}catch(e:Exception){mutableListOf()};l.add(nJ);val uJ=gson.toJson(l);historyPrefs.edit().putString(KEY_TRIP_HISTORY,uJ).apply()}catch(e:Exception){Log.e(TAG,"ERRO salvar histórico:${e.message}",e)} }

    // --- Aparência ---
    private fun applyAppearanceSettings(fontSizePercent: Int, transparencyPercent: Int) { applyAppearanceSettingsToView(mainOverlayView, fontSizePercent, transparencyPercent); applyAppearanceSettingsToView(trackingOverlayView, null, transparencyPercent); applyAppearanceSettingsToView(quickMenuView, null, transparencyPercent) }
    private fun applyAppearanceSettingsToView(view: View?, fontSizePercent: Int? = null, transparencyPercent: Int? = null) { view?:return; val fT=transparencyPercent?:SettingsActivity.getTransparency(this); val a=(1.0f-(fT/100f)).coerceIn(0.0f,1.0f); when(view){is OverlayView->{val fFS=fontSizePercent?:SettingsActivity.getFontSize(this);val sc=fFS/100f;view.updateFontSize(sc);view.updateAlpha(a)}; is TrackingOverlayView->{view.alpha=a}; is MenuView->{view.alpha=a}; else->{view.alpha=a}} }
    private fun updateLayouts(){ if(isMainOverlayAdded&&mainOverlayView!=null)try{windowManager?.updateViewLayout(mainOverlayView,mainLayoutParams)}catch(e:Exception){}; if(isTrackingOverlayAdded&&trackingOverlayView!=null)try{windowManager?.updateViewLayout(trackingOverlayView,trackingLayoutParams)}catch(e:Exception){}; if(isFloatingIconAdded&&floatingIconView!=null)try{windowManager?.updateViewLayout(floatingIconView,floatingIconLayoutParams)}catch(e:Exception){}; if(isQuickMenuAdded&&quickMenuView!=null)try{windowManager?.updateViewLayout(quickMenuView,menuLayoutParams)}catch(e:Exception){} }

    // --- Turno ---
    private fun loadShiftState(){ isShiftActive=shiftPrefs.getBoolean(KEY_SHIFT_ACTIVE,false);isShiftPaused=shiftPrefs.getBoolean(KEY_SHIFT_PAUSED,false);shiftStartTimeMillis=shiftPrefs.getLong(KEY_SHIFT_START_TIME,0L);shiftLastPauseOrResumeTimeMillis=shiftPrefs.getLong(KEY_SHIFT_LAST_PAUSE_OR_RESUME_TIME,0L);shiftAccumulatedWorkedTimeMillis=shiftPrefs.getLong(KEY_SHIFT_ACCUMULATED_WORKED_TIME,0L);shiftTotalEarnings=shiftPrefs.getFloat(KEY_SHIFT_TOTAL_EARNINGS,0f).toDouble(); if(isShiftActive&&!isShiftPaused&&shiftLastPauseOrResumeTimeMillis>0){val tS=System.currentTimeMillis()-shiftLastPauseOrResumeTimeMillis;val maxT=TimeUnit.MINUTES.toMillis(5);if(tS in 1..<maxT){} isShiftPaused=true;shiftLastPauseOrResumeTimeMillis=System.currentTimeMillis();saveShiftState()}else if(isShiftActive&&!isShiftPaused&&shiftLastPauseOrResumeTimeMillis==0L&&shiftStartTimeMillis>0L){shiftLastPauseOrResumeTimeMillis=System.currentTimeMillis();isShiftPaused=true;saveShiftState()} }
    private fun saveShiftState(){ if(!isShiftActive&&shiftStartTimeMillis==0L){shiftPrefs.edit().clear().apply();Log.i(TAG, "Estado turno limpo.");return}; shiftPrefs.edit().apply{putBoolean(KEY_SHIFT_ACTIVE,isShiftActive);putBoolean(KEY_SHIFT_PAUSED,isShiftPaused);putLong(KEY_SHIFT_START_TIME,shiftStartTimeMillis);putLong(KEY_SHIFT_LAST_PAUSE_OR_RESUME_TIME,shiftLastPauseOrResumeTimeMillis);putLong(KEY_SHIFT_ACCUMULATED_WORKED_TIME,shiftAccumulatedWorkedTimeMillis);putFloat(KEY_SHIFT_TOTAL_EARNINGS,shiftTotalEarnings.toFloat());apply()}; Log.d(TAG, "Estado turno salvo: Ativo=$isShiftActive, Pausado=$isShiftPaused, GanhosREAIS=${shiftTotalEarnings}") }
    private fun handleToggleShiftState(){ val cT=System.currentTimeMillis();if(!isShiftActive){isShiftActive=true;isShiftPaused=false;shiftStartTimeMillis=cT;shiftLastPauseOrResumeTimeMillis=cT;shiftAccumulatedWorkedTimeMillis=0L;shiftTotalEarnings=0.0;startShiftTimer()}else{if(isShiftPaused){isShiftPaused=false;shiftLastPauseOrResumeTimeMillis=cT;startShiftTimer()}else{isShiftPaused=true;val wM=cT-shiftLastPauseOrResumeTimeMillis;if(wM>0){shiftAccumulatedWorkedTimeMillis+=wM};shiftLastPauseOrResumeTimeMillis=cT;stopShiftTimer()}};updateMenuViewShiftUI();updateShiftNotification();saveShiftState() }

    private fun handleEndShift(saveSummary:Boolean=true){
        if(!isShiftActive){return}
        val eT=System.currentTimeMillis(); Log.i(TAG,">>> TURNO TERMINADO <<<"); stopShiftTimer()
        var fWT=shiftAccumulatedWorkedTimeMillis
        if(!isShiftPaused){val lSM=eT-shiftLastPauseOrResumeTimeMillis;if(lSM>0){fWT+=lSM}}
        val fFT=formatDuration(fWT)

        val averagePerHourNumericAtEnd = getNumericShiftAveragePerHourValue(fWT) // Média REAL no final
        val averagePerHourTextAtEnd = calculateCurrentShiftAveragePerHourString(averagePerHourNumericAtEnd) // Média REAL formatada
        // Ganhos estimados no final para o log
        val finalEstimatedEarnings = if (averagePerHourNumericAtEnd != null && fWT > 0) {
            averagePerHourNumericAtEnd * (fWT / 3600000.0)
        } else {
            0.0
        }

        Log.i(TAG,"Resumo Turno: Dur=$fFT, Ganhos REAIS=${currencyFormatter.format(shiftTotalEarnings)}, Ganhos ESTIMADOS=${currencyFormatter.format(finalEstimatedEarnings)}, Média REAL=$averagePerHourTextAtEnd");
        isShiftActive=false;isShiftPaused=false;
        if(saveSummary){saveShiftState()}else{shiftPrefs.edit().clear().apply(); Log.i(TAG,"Estado turno limpo (sem salvar).")}
        updateMenuViewShiftUI();updateShiftNotification(); // Atualiza UI DEPOIS de potencialmente limpar/salvar
        shiftTotalEarnings=0.0; shiftAccumulatedWorkedTimeMillis=0L; shiftStartTimeMillis=0L; shiftLastPauseOrResumeTimeMillis=0L
    }

    private fun startShiftTimer(){ shiftTimerRunnable?.let{shiftTimerHandler.removeCallbacks(it);shiftTimerHandler.post(it);}?:run{setupShiftTimerRunnable();shiftTimerHandler.post(shiftTimerRunnable!!)}; updateShiftNotification() }
    private fun stopShiftTimer(){ shiftTimerRunnable?.let{shiftTimerHandler.removeCallbacks(it)}; updateShiftNotification() }
    private fun calculateCurrentWorkedTimeMillis():Long{ if(!isShiftActive)return 0L;var cWT=shiftAccumulatedWorkedTimeMillis;if(!isShiftPaused){val tS=System.currentTimeMillis()-shiftLastPauseOrResumeTimeMillis;if(tS>0){cWT+=tS}};return max(0L,cWT) }
    private fun formatDuration(ms:Long):String{ if(ms<0)return"00:00:00";val h=TimeUnit.MILLISECONDS.toHours(ms);val m=TimeUnit.MILLISECONDS.toMinutes(ms)%60;val s=TimeUnit.MILLISECONDS.toSeconds(ms)%60;return String.format(Locale.getDefault(),"%02d:%02d:%02d",h,m,s) }

    /**
     * Calcula a MÉDIA €/hora REAL do turno, retornando o valor numérico ou null.
     * Baseia-se em `shiftTotalEarnings` (ganhos reais) e no tempo trabalhado.
     */
    private fun getNumericShiftAveragePerHourValue(currentWorkedMillisParam: Long? = null): Double? {
        if (!isShiftActive || shiftStartTimeMillis == 0L) return null
        val totalRealEarnings = shiftTotalEarnings // Usa os ganhos REAIS para o cálculo da média
        val workedMillis = currentWorkedMillisParam ?: calculateCurrentWorkedTimeMillis()

        if (workedMillis < 5000L) { // Se tempo muito curto (ex: < 5 segundos)
            // Se houver ganhos, a média é potencialmente muito volátil, retorna null para indicar "calculando..."
            // Se não houver ganhos, a média é 0.0
            return if (totalRealEarnings > 0.0) null else 0.0
        }

        val workedHours = workedMillis / 3600000.0 // Millis para horas (Double)

        return if (workedHours > 0) {
            if (totalRealEarnings > 0) {
                totalRealEarnings / workedHours
            } else {
                0.0 // Trabalhou mas não ganhou nada (ou ganhos são 0), média é 0.
            }
        } else {
            // Caso de workedHours ser 0 ou negativo (improvável se workedMillis >= 5000L)
            if (totalRealEarnings > 0.0) null else 0.0
        }
    }

    /**
     * Formata a MÉDIA €/hora REAL (baseada em shiftTotalEarnings) para exibição.
     * Recebe o valor numérico da média para evitar recálculo.
     */
    private fun calculateCurrentShiftAveragePerHourString(averageNumeric: Double?): String {
        return if (averageNumeric != null && averageNumeric.isFinite()) {
            // Usa averageDecimalFormat para formatar a média, depois concatena a unidade
            averageDecimalFormat.format(averageNumeric) + " €/h"
        } else {
            // Lógica para placeholder da média
            val workedMillis = calculateCurrentWorkedTimeMillis()
            if (isShiftActive && shiftTotalEarnings > 0.0 && workedMillis < 5000L) { // Média instável
                "-- €/h (calc..)"
            } else if (isShiftActive && averageNumeric == 0.0) { // Média é explicitamente 0
                "0.0 €/h"
            }
            else { // Turno inativo ou média não pôde ser calculada
                "-- €/h"
            }
        }
    }


    // Atualiza a UI do MenuView (MODIFICADO para nova lógica de ganhos)
    private fun updateMenuViewShiftUI() {
        quickMenuView?.let { menu ->
            val statusResId = if (!isShiftActive) R.string.shift_status_none
            else if (isShiftPaused) R.string.shift_status_paused
            else R.string.shift_status_active
            val workedTimeMillis = calculateCurrentWorkedTimeMillis()
            val formattedTime = formatDuration(workedTimeMillis)

            // 1. Obter a média €/h atual (como valor numérico, baseada em ganhos REAIS)
            val averagePerHourNumeric = getNumericShiftAveragePerHourValue(workedTimeMillis)

            // 2. Calcular ganhos ESTIMADOS com base na média e tempo
            val estimatedEarningsValue = if (averagePerHourNumeric != null && workedTimeMillis > 0 && averagePerHourNumeric.isFinite()) {
                val workedHours = workedTimeMillis / 3600000.0
                averagePerHourNumeric * workedHours
            } else {
                0.0
            }
            val formattedEstimatedEarnings = currencyFormatter.format(estimatedEarningsValue)

            // 3. Formata a média €/h REAL para exibição
            val averagePerHourStringForDisplay = calculateCurrentShiftAveragePerHourString(averagePerHourNumeric)


            menu.updateShiftStatus(getString(statusResId), isShiftActive, isShiftPaused)
            menu.updateShiftTimer(formattedTime)
            menu.updateShiftEarnings(formattedEstimatedEarnings) // Mostra ganhos estimados
            menu.updateShiftAverage(averagePerHourStringForDisplay) // Mostra a média real
        }
    }

    private fun updateShiftNotification(){ val txt=when{isCurrentlyTracking->"Acompanhando Viagem...";isShiftActive&&!isShiftPaused->"Turno ativo (${formatDuration(calculateCurrentWorkedTimeMillis())})";isShiftActive&&isShiftPaused->"Turno pausado";else->"SmartDriver pronto"};val isAct=isCurrentlyTracking||(isShiftActive&&!isShiftPaused);updateNotification(txt,isAct)}

}