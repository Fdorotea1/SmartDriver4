package com.example.smartdriver

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.smartdriver.databinding.ActivityMainBinding
import com.example.smartdriver.overlay.OverlayService

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private lateinit var mediaProjectionLauncher: ActivityResultLauncher<Intent>
    private lateinit var accessibilitySettingsLauncher: ActivityResultLauncher<Intent>
    private lateinit var overlaySettingsLauncher: ActivityResultLauncher<Intent>
    private lateinit var notificationPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var sharedPreferences: SharedPreferences

    // Receiver para desligar a app a partir do serviço/menu
    private val shutdownReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_SHUTDOWN_APP) {
                Log.w(TAG, "Recebido broadcast $ACTION_SHUTDOWN_APP. Encerrando MainActivity.")
                finishAffinity()
            }
        }
    }

    companion object {
        internal const val TAG = "MainActivity"
        internal const val PREFS_NAME = "SmartDriverPrefs"
        internal const val KEY_APP_ACTIVE = "appActive"
        internal const val KEY_SAVE_IMAGES = "save_images"
        const val ACTION_SHUTDOWN_APP = "com.example.smartdriver.ACTION_SHUTDOWN_APP"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Log.d(TAG, "onCreate")

        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        setupResultLaunchers()
        setupButtonClickListeners()
        setupSwitches()
        requestNeededPermissions()

        // Registar o receiver de shutdown
        val filter = IntentFilter(ACTION_SHUTDOWN_APP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(shutdownReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(shutdownReceiver, filter)
        }

        // Normaliza o estado do switch ao arranque (evita “ligado” enganoso)
        normalizeInitialSwitchState()
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume - Atualizando UI e estado")
        updatePermissionStatusUI()
        normalizeInitialSwitchState()
        updateAppStatusUI(sharedPreferences.getBoolean(KEY_APP_ACTIVE, false))
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(shutdownReceiver)
            Log.d(TAG, "Shutdown receiver desregistrado.")
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Receiver já estava desregistado: ${e.message}")
        }
    }

    // ---------- Listeners dos botões ----------
    private fun setupButtonClickListeners() {
        // 1) Acessibilidade
        binding.accessibilityButton.setOnClickListener {
            Log.d(TAG, "A abrir definições de Acessibilidade")
            try {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                Toast.makeText(this, "Encontra 'SmartDriver Service' e ativa-o", Toast.LENGTH_LONG).show()
                accessibilitySettingsLauncher.launch(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao abrir acessibilidade: ${e.message}")
                Toast.makeText(this, "Não foi possível abrir as definições.", Toast.LENGTH_SHORT).show()
            }
        }

        // 2) Overlay
        binding.overlayButton.setOnClickListener {
            Log.d(TAG, "A abrir definições de Overlay")
            if (!Settings.canDrawOverlays(this)) {
                try {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    overlaySettingsLauncher.launch(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Erro ao abrir overlay: ${e.message}")
                    Toast.makeText(this, "Não foi possível abrir as definições.", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Permissão de overlay já concedida", Toast.LENGTH_SHORT).show()
            }
        }

        // Configurações
        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Histórico
        binding.historyButton.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }

        // Encerrar app
        binding.shutdownButton.setOnClickListener {
            Log.w(TAG, "Shutdown via MainActivity")
            stopScreenCaptureService()
            stopOverlayService()
            MediaProjectionData.clear()
            persistAppActive(false)
            binding.appStatusSwitch.isChecked = false
            updatePermissionStatusUI()
            updateAppStatusUI(false)
            finishAffinity()
        }
    }

    // ---------- Launchers de resultado ----------
    private fun setupResultLaunchers() {
        // Acessibilidade
        accessibilitySettingsLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                Handler(Looper.getMainLooper()).postDelayed({ updatePermissionStatusUI() }, 300)
            }

        // Overlay
        overlaySettingsLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                Handler(Looper.getMainLooper()).postDelayed({ updatePermissionStatusUI() }, 300)
            }

        // MediaProjection (captura de ecrã)
        mediaProjectionLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                    Log.d(TAG, "Permissão de captura CONCEDIDA")
                    MediaProjectionData.resultCode = result.resultCode
                    MediaProjectionData.resultData = result.data?.clone() as Intent?
                    Toast.makeText(this, "Permissão de captura concedida!", Toast.LENGTH_SHORT).show()

                    // Se o utilizador pretendia ligar, iniciamos serviços e só depois persistimos ON
                    if (binding.appStatusSwitch.isChecked) {
                        val started = startServicesIfReady()
                        if (started) {
                            persistAppActive(true)
                            updateAppStatusUI(true)
                        } else {
                            // Algo ainda falta → volta a OFF e não persiste
                            binding.appStatusSwitch.isChecked = false
                            persistAppActive(false)
                            updateAppStatusUI(false)
                        }
                    }
                } else {
                    Log.w(TAG, "Permissão de captura NEGADA")
                    MediaProjectionData.clear()
                    Toast.makeText(this, "Permissão de captura é necessária.", Toast.LENGTH_LONG).show()
                    // Garante OFF e não persiste ON
                    binding.appStatusSwitch.isChecked = false
                    persistAppActive(false)
                    updateAppStatusUI(false)
                }
                updatePermissionStatusUI()
            }

        // Notificações (Android 13+)
        notificationPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
                if (isGranted) Log.i(TAG, "Permissão POST_NOTIFICATIONS concedida.")
                else Log.w(TAG, "Permissão POST_NOTIFICATIONS negada.")
            }
    }

    // ---------- Switch principal ----------
    private fun setupSwitches() {
        // Lê “ativo” guardado
        val savedActive = sharedPreferences.getBoolean(KEY_APP_ACTIVE, false)
        binding.appStatusSwitch.isChecked = savedActive
        updateAppStatusUI(savedActive)

        val saveImagesEnabled = sharedPreferences.getBoolean(KEY_SAVE_IMAGES, false)
        binding.saveImagesSwitch.isChecked = saveImagesEnabled

        // ON/OFF do Assistente
        binding.appStatusSwitch.setOnCheckedChangeListener { _, isChecked ->
            Log.d(TAG, "AppStatusSwitch -> $isChecked")

            if (isChecked) {
                // NÃO persistimos ainda; primeiro verificamos permissões/arranque
                val allOk = hasAllCorePermissions()
                if (allOk) {
                    val started = startServicesIfReady()
                    if (started) {
                        persistAppActive(true)
                        updateAppStatusUI(true)
                    } else {
                        // Falhou arrancar → volta a OFF
                        binding.appStatusSwitch.isChecked = false
                        persistAppActive(false)
                        updateAppStatusUI(false)
                    }
                } else {
                    // Falta algo: corre o fluxo de permissões
                    checkPermissionsAndStartOrRequestCapture()
                }
            } else {
                // OFF: paramos serviços e persistimos OFF
                stopScreenCaptureService()
                stopOverlayService()
                MediaProjectionData.clear()
                persistAppActive(false)
                updatePermissionStatusUI()
                updateAppStatusUI(false)
            }
        }

        // Guardar screenshots para debug
        binding.saveImagesSwitch.setOnCheckedChangeListener { _, isChecked ->
            sharedPreferences.edit().putBoolean(KEY_SAVE_IMAGES, isChecked).apply()
            updateScreenCaptureSaveSetting(isChecked)
            Toast.makeText(
                this,
                if (isChecked) "Capturas de oferta serão guardadas" else "Capturas NÃO serão guardadas",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // Garante que o switch inicial não fica “ligado” se faltar qualquer permissão.
    // Se estava guardado como ativo e todas as permissões existem, arranca automaticamente.
    private fun normalizeInitialSwitchState() {
        val savedActive = sharedPreferences.getBoolean(KEY_APP_ACTIVE, false)
        val allOk = hasAllCorePermissions()

        when {
            savedActive && allOk -> {
                // Auto-start silencioso (evita teres de desligar/ligar)
                binding.appStatusSwitch.isChecked = true
                val started = startServicesIfReady()
                if (!started) {
                    binding.appStatusSwitch.isChecked = false
                    persistAppActive(false)
                } else {
                    persistAppActive(true)
                }
            }
            savedActive && !allOk -> {
                // Estava guardado ON mas não há permissões → força OFF
                binding.appStatusSwitch.isChecked = false
                persistAppActive(false)
            }
            else -> {
                // Mantém OFF por omissão
                binding.appStatusSwitch.isChecked = false
                persistAppActive(false)
            }
        }

        updatePermissionStatusUI()
        updateAppStatusUI(sharedPreferences.getBoolean(KEY_APP_ACTIVE, false))
    }

    // ---------- Permissões ----------
    private fun checkPermissionsAndStartOrRequestCapture() {
        Log.d(TAG, "Verificar permissões para iniciar")
        val isAccessibilityEnabled = isAccessibilityServiceEnabled()
        val canDrawOverlays = Settings.canDrawOverlays(this)
        val hasCapture = MediaProjectionData.resultCode == Activity.RESULT_OK && MediaProjectionData.resultData != null

        if (!isAccessibilityEnabled) {
            Toast.makeText(this, "Ative o Serviço de Acessibilidade (Item 1).", Toast.LENGTH_SHORT).show()
            binding.appStatusSwitch.isChecked = false
            persistAppActive(false)
            updateAppStatusUI(false)
            return
        }
        if (!canDrawOverlays) {
            Toast.makeText(this, "Permita o Overlay (Item 2).", Toast.LENGTH_SHORT).show()
            binding.appStatusSwitch.isChecked = false
            persistAppActive(false)
            updateAppStatusUI(false)
            return
        }
        if (!hasCapture) {
            Toast.makeText(this, "Permita a Captura de Ecrã (Item 3).", Toast.LENGTH_SHORT).show()
            requestScreenCapturePermission()
            return
        }

        // Tudo pronto → arranca e persiste ON
        val started = startServicesIfReady()
        if (started) {
            persistAppActive(true)
            updateAppStatusUI(true)
        } else {
            binding.appStatusSwitch.isChecked = false
            persistAppActive(false)
            updateAppStatusUI(false)
        }
    }

    private fun requestScreenCapturePermission() {
        try {
            MediaProjectionData.clear()
            mediaProjectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao pedir captura: ${e.message}")
            Toast.makeText(this, "Não foi possível solicitar permissão de captura.", Toast.LENGTH_SHORT).show()
            binding.appStatusSwitch.isChecked = false
            persistAppActive(false)
            updateAppStatusUI(false)
        }
    }

    // ---------- UI de permissões ----------
    private fun updatePermissionStatusUI() {
        val isAccessibilityEnabled = isAccessibilityServiceEnabled()
        binding.accessibilityStatusTextView.text =
            "1. Acessibilidade: ${if (isAccessibilityEnabled) "Ativo ✅" else "Inativo ❌"}"
        binding.accessibilityStatusTextView.setTextColor(
            ContextCompat.getColor(this, if (isAccessibilityEnabled) R.color.black else R.color.gray_inactive)
        )
        binding.accessibilityButton.isEnabled = !isAccessibilityEnabled
        binding.accessibilityButton.alpha = if (isAccessibilityEnabled) 0.5f else 1.0f

        val canDrawOverlays = Settings.canDrawOverlays(this)
        binding.overlayStatusTextView.text =
            "2. Overlay: ${if (canDrawOverlays) "Permitido ✅" else "Não Permitido ❌"}"
        binding.overlayStatusTextView.setTextColor(
            ContextCompat.getColor(this, if (canDrawOverlays) R.color.black else R.color.gray_inactive)
        )
        binding.overlayButton.isEnabled = !canDrawOverlays
        binding.overlayButton.alpha = if (canDrawOverlays) 0.5f else 1.0f

        val hasCapture = MediaProjectionData.resultCode == Activity.RESULT_OK && MediaProjectionData.resultData != null
        binding.captureStatusTextView.text =
            "3. Captura: ${if (hasCapture) "Permitida (Sessão) ✅" else "Não Permitida ❌"}"
        binding.captureStatusTextView.setTextColor(
            ContextCompat.getColor(this, if (hasCapture) R.color.black else R.color.gray_inactive)
        )
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = "$packageName/${SmartDriverAccessibilityService::class.java.name}"
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        return enabled?.split(':')?.contains(expected) ?: false
    }

    private fun updateAppStatusUI(isActive: Boolean) {
        binding.appStatusText.text = getString(if (isActive) R.string.app_status_on else R.string.app_status_off)
        binding.appStatusText.setTextColor(
            ContextCompat.getColor(this, if (isActive) R.color.black else R.color.gray_inactive)
        )
    }

    // ---------- Serviços ----------
    /**
     * Tenta iniciar os serviços necessários. Só deve ser chamado quando as permissões estão OK.
     * @return true se ambos (overlay e captura) ficaram a correr; false caso contrário.
     */
    private fun startServicesIfReady(): Boolean {
        Log.d(TAG, "startServicesIfReady")
        if (!hasAllCorePermissions()) {
            Log.e(TAG, "startServicesIfReady chamado sem permissões completas.")
            return false
        }
        val overlayOk = startOverlayService()
        val captureOk = startScreenCaptureService()
        return overlayOk && captureOk
    }

    private fun startScreenCaptureService(): Boolean {
        if (MediaProjectionData.resultCode != Activity.RESULT_OK || MediaProjectionData.resultData == null) {
            Log.e(TAG, "Dados de projeção inválidos. Não iniciar ScreenCaptureService.")
            Toast.makeText(this, "Erro de permissão de captura.", Toast.LENGTH_SHORT).show()
            return false
        }
        if (ScreenCaptureService.isRunning.get()) {
            Log.d(TAG, "ScreenCaptureService já a correr. Atualizando saveImages.")
            updateScreenCaptureSaveSetting(binding.saveImagesSwitch.isChecked)
            return true
        }
        Log.d(TAG, "Iniciando ScreenCaptureService…")
        val intent = Intent(this, ScreenCaptureService::class.java).apply {
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, MediaProjectionData.resultCode)
            putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, MediaProjectionData.resultData)
            putExtra(KEY_SAVE_IMAGES, binding.saveImagesSwitch.isChecked)
        }
        return try {
            ContextCompat.startForegroundService(this, intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao iniciar ScreenCaptureService: ${e.message}", e)
            Toast.makeText(this, "Falha a iniciar captura.", Toast.LENGTH_SHORT).show()
            false
        }
    }

    private fun stopScreenCaptureService() {
        if (!ScreenCaptureService.isRunning.get()) return
        Log.d(TAG, "Parando ScreenCaptureService…")
        val intent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_STOP_CAPTURE
        }
        try {
            stopService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Erro a parar ScreenCaptureService: ${e.message}", e)
        }
    }

    private fun startOverlayService(): Boolean {
        if (!Settings.canDrawOverlays(this)) {
            Log.e(TAG, "Sem permissão de overlay.")
            Toast.makeText(this, "Permissão de overlay necessária.", Toast.LENGTH_SHORT).show()
            return false
        }
        if (OverlayService.isRunning.get()) {
            Log.d(TAG, "OverlayService já a correr.")
            return true
        }
        Log.d(TAG, "Iniciando OverlayService…")
        val intent = Intent(this, OverlayService::class.java)
        return try {
            ContextCompat.startForegroundService(this, intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao iniciar OverlayService: ${e.message}", e)
            Toast.makeText(this, "Falha a iniciar overlay.", Toast.LENGTH_SHORT).show()
            false
        }
    }

    private fun stopOverlayService() {
        if (!OverlayService.isRunning.get()) return
        Log.d(TAG, "Parando OverlayService…")
        val intent = Intent(this, OverlayService::class.java)
        try {
            stopService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Erro a parar OverlayService: ${e.message}", e)
        }
    }

    private fun updateScreenCaptureSaveSetting(shouldSave: Boolean) {
        if (ScreenCaptureService.isRunning.get()) {
            val intent = Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_UPDATE_SETTINGS
                putExtra(KEY_SAVE_IMAGES, shouldSave)
            }
            try {
                startService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao enviar UPDATE_SETTINGS: ${e.message}", e)
            }
        }
    }

    // ---------- Auxiliares ----------
    private fun hasAllCorePermissions(): Boolean {
        val accessibility = isAccessibilityServiceEnabled()
        val overlay = Settings.canDrawOverlays(this)
        val capture = MediaProjectionData.resultCode == Activity.RESULT_OK && MediaProjectionData.resultData != null
        return accessibility && overlay && capture
    }

    private fun persistAppActive(active: Boolean) {
        sharedPreferences.edit().putBoolean(KEY_APP_ACTIVE, active).apply()
    }

    private fun requestNeededPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
