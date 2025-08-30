package com.example.smartdriver

import android.Manifest
import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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

    // Guards anti-duplo consentimento
    private var isRequestingProjection = false
    private var lastProjectionLaunchAt = 0L
    private val PROJECTION_LAUNCH_COOLDOWN_MS = 2500L

    // Evitar loops ao sincronizar switches por código
    private var isUpdatingPermSwitches = false

    // Receiver para desligar a app a partir do serviço/menu
    private val shutdownReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_SHUTDOWN_APP) {
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

        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        setupResultLaunchers()
        setupButtonClickListeners()
        setupSwitches()
        setupPermissionSwitches()
        requestNeededPermissions()

        val filter = IntentFilter(ACTION_SHUTDOWN_APP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(shutdownReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(shutdownReceiver, filter)
        }

        normalizeInitialSwitchState()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatusUI()
        updateAppStatusUI(sharedPreferences.getBoolean(KEY_APP_ACTIVE, false))
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(shutdownReceiver) } catch (_: IllegalArgumentException) {}
    }

    // ---------- Botões simples ----------
    private fun setupButtonClickListeners() {
        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.historyButton.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        binding.shutdownButton.setOnClickListener {
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

    // ---------- Launchers ----------
    private fun setupResultLaunchers() {
        accessibilitySettingsLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                Handler(Looper.getMainLooper()).postDelayed({ updatePermissionStatusUI() }, 300)
            }
        overlaySettingsLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                Handler(Looper.getMainLooper()).postDelayed({ updatePermissionStatusUI() }, 300)
            }
        mediaProjectionLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                    MediaProjectionData.resultCode = result.resultCode
                    MediaProjectionData.resultData = result.data?.clone() as Intent?
                    Toast.makeText(this, "Permissão de captura concedida!", Toast.LENGTH_SHORT).show()
                    if (binding.appStatusSwitch.isChecked) {
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
                } else {
                    MediaProjectionData.clear()
                    Toast.makeText(this, "Permissão de captura é necessária.", Toast.LENGTH_LONG).show()
                }
                updatePermissionStatusUI()
                isRequestingProjection = false
                lastProjectionLaunchAt = SystemClock.elapsedRealtime()
            }

        notificationPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }
    }

    // ---------- Switch principal ----------
    private fun setupSwitches() {
        val savedActive = sharedPreferences.getBoolean(KEY_APP_ACTIVE, false)
        binding.appStatusSwitch.isChecked = savedActive
        updateAppStatusUI(savedActive)

        val saveImagesEnabled = sharedPreferences.getBoolean(KEY_SAVE_IMAGES, false)
        binding.saveImagesSwitch.isChecked = saveImagesEnabled

        binding.appStatusSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                val allOk = hasAllCorePermissions()
                if (allOk) {
                    val started = startServicesIfReady()
                    if (started) {
                        persistAppActive(true)
                        updateAppStatusUI(true)
                    } else {
                        binding.appStatusSwitch.isChecked = false
                        persistAppActive(false)
                        updateAppStatusUI(false)
                    }
                } else {
                    checkPermissionsAndStartOrRequestCapture()
                }
            } else {
                stopScreenCaptureService()
                stopOverlayService()
                MediaProjectionData.clear()
                persistAppActive(false)
                updatePermissionStatusUI()
                updateAppStatusUI(false)
            }
        }

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

    // ---------- Switches de permissões ----------
    private fun setupPermissionSwitches() {
        // Acessibilidade
        binding.accessibilityPermSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingPermSwitches) return@setOnCheckedChangeListener
            val enabled = isAccessibilityServiceEnabled()
            when {
                isChecked && !enabled -> {
                    Toast.makeText(this, "Ativar em Definições > Acessibilidade", Toast.LENGTH_SHORT).show()
                    accessibilitySettingsLauncher.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    syncPermissionSwitches()
                }
                !isChecked && enabled -> {
                    Toast.makeText(this, "Desative manualmente o serviço em Acessibilidade.", Toast.LENGTH_SHORT).show()
                    accessibilitySettingsLauncher.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    syncPermissionSwitches()
                }
            }
        }

        // Overlay
        binding.overlayPermSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingPermSwitches) return@setOnCheckedChangeListener
            val canDraw = Settings.canDrawOverlays(this)
            when {
                isChecked && !canDraw -> {
                    Toast.makeText(this, "Conceda a permissão de sobreposição.", Toast.LENGTH_SHORT).show()
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                    overlaySettingsLauncher.launch(intent)
                    syncPermissionSwitches()
                }
                !isChecked && canDraw -> {
                    Toast.makeText(this, "Desative a permissão de overlay nas definições.", Toast.LENGTH_SHORT).show()
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                    overlaySettingsLauncher.launch(intent)
                    syncPermissionSwitches()
                }
            }
        }
    }

    private fun syncPermissionSwitches() {
        isUpdatingPermSwitches = true
        try {
            val acc = isAccessibilityServiceEnabled()
            val ovl = Settings.canDrawOverlays(this)
            val cap = MediaProjectionData.resultCode == Activity.RESULT_OK && MediaProjectionData.resultData != null

            // switches + desativação quando já concedido
            binding.accessibilityPermSwitch.isChecked = acc
            binding.accessibilityPermSwitch.isEnabled = !acc
            binding.accessibilityStatusTextView.setTextColor(
                ContextCompat.getColor(this, if (acc) R.color.gray_inactive else R.color.black)
            )

            binding.overlayPermSwitch.isChecked = ovl
            binding.overlayPermSwitch.isEnabled = !ovl
            binding.overlayStatusTextView.setTextColor(
                ContextCompat.getColor(this, if (ovl) R.color.gray_inactive else R.color.black)
            )

            // Captura: só estado (sem switch)
            binding.captureStatusText.text = if (cap) "Sessão ativa" else "Não permitida"
            binding.captureStatusText.setTextColor(
                ContextCompat.getColor(this, if (cap) R.color.gray_inactive else R.color.black)
            )
        } finally {
            isUpdatingPermSwitches = false
        }
    }

    private fun normalizeInitialSwitchState() {
        val savedActive = sharedPreferences.getBoolean(KEY_APP_ACTIVE, false)
        val allOk = hasAllCorePermissions()
        if (savedActive && allOk) {
            binding.appStatusSwitch.isChecked = true
            val started = startServicesIfReady()
            if (!started) {
                binding.appStatusSwitch.isChecked = false
                persistAppActive(false)
            } else persistAppActive(true)
        } else {
            binding.appStatusSwitch.isChecked = false
            persistAppActive(false)
        }
        updatePermissionStatusUI()
        updateAppStatusUI(sharedPreferences.getBoolean(KEY_APP_ACTIVE, false))
    }

    // ---------- Permissões ----------
    private fun checkPermissionsAndStartOrRequestCapture() {
        val now = SystemClock.elapsedRealtime()
        if (isRequestingProjection || (now - lastProjectionLaunchAt) < PROJECTION_LAUNCH_COOLDOWN_MS) return

        val acc = isAccessibilityServiceEnabled()
        val ovl = Settings.canDrawOverlays(this)
        val cap = MediaProjectionData.resultCode == Activity.RESULT_OK && MediaProjectionData.resultData != null

        if (!acc) {
            Toast.makeText(this, "Ative o Serviço de Acessibilidade (Item 1).", Toast.LENGTH_SHORT).show()
            binding.appStatusSwitch.isChecked = false
            persistAppActive(false)
            updateAppStatusUI(false); return
        }
        if (!ovl) {
            Toast.makeText(this, "Permita o Overlay (Item 2).", Toast.LENGTH_SHORT).show()
            binding.appStatusSwitch.isChecked = false
            persistAppActive(false)
            updateAppStatusUI(false); return
        }
        if (!cap) {
            Toast.makeText(this, "Permita a Captura de Ecrã (Item 3).", Toast.LENGTH_SHORT).show()
            requestScreenCapturePermission(); return
        }

        val started = startServicesIfReady()
        if (started) { persistAppActive(true); updateAppStatusUI(true) }
        else { binding.appStatusSwitch.isChecked = false; persistAppActive(false); updateAppStatusUI(false) }
    }

    private fun requestScreenCapturePermission() {
        val now = SystemClock.elapsedRealtime()
        if (isRequestingProjection || (now - lastProjectionLaunchAt) < PROJECTION_LAUNCH_COOLDOWN_MS) return
        try {
            isRequestingProjection = true
            MediaProjectionData.clear()
            mediaProjectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
        } catch (_: Exception) {
            isRequestingProjection = false
            Toast.makeText(this, "Não foi possível solicitar permissão de captura.", Toast.LENGTH_SHORT).show()
            updatePermissionStatusUI()
        }
    }

    // ---------- UI ----------
    private fun updatePermissionStatusUI() = syncPermissionSwitches()

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
    private fun startServicesIfReady(): Boolean {
        if (!hasAllCorePermissions()) return false
        val captureOk = startScreenCaptureService()
        val overlayOk = if (captureOk) startOverlayService() else false
        return overlayOk && captureOk
    }

    private fun startScreenCaptureService(): Boolean {
        if (MediaProjectionData.resultCode != Activity.RESULT_OK || MediaProjectionData.resultData == null) {
            Toast.makeText(this, "Erro de permissão de captura.", Toast.LENGTH_SHORT).show()
            return false
        }
        if (ScreenCaptureService.isRunning.get()) return true
        val intent = Intent(this, ScreenCaptureService::class.java).apply {
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, MediaProjectionData.resultCode)
            putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, MediaProjectionData.resultData)
            putExtra(KEY_SAVE_IMAGES, binding.saveImagesSwitch.isChecked)
        }
        return try { ContextCompat.startForegroundService(this, intent); true }
        catch (_: Exception) { Toast.makeText(this, "Falha a iniciar captura.", Toast.LENGTH_SHORT).show(); false }
    }

    private fun stopScreenCaptureService() {
        if (!ScreenCaptureService.isRunning.get()) return
        val intent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_STOP_CAPTURE
        }
        try { stopService(intent) } catch (_: Exception) {}
    }

    private fun startOverlayService(): Boolean {
        if (!Settings.canDrawOverlays(this)) return false
        if (OverlayService.isRunning.get()) return true
        val intent = Intent(this, OverlayService::class.java)
        return try { ContextCompat.startForegroundService(this, intent); true }
        catch (_: Exception) { Toast.makeText(this, "Falha a iniciar overlay.", Toast.LENGTH_SHORT).show(); false }
    }

    private fun stopOverlayService() {
        if (!OverlayService.isRunning.get()) return
        val intent = Intent(this, OverlayService::class.java)
        try { stopService(intent) } catch (_: Exception) {}
    }

    private fun updateScreenCaptureSaveSetting(shouldSave: Boolean) {
        if (ScreenCaptureService.isRunning.get()) {
            val intent = Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_UPDATE_SETTINGS
                putExtra(KEY_SAVE_IMAGES, shouldSave)
            }
            try { startService(intent) } catch (_: Exception) {}
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
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_APP_ACTIVE, active).apply()
    }

    private fun requestNeededPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
