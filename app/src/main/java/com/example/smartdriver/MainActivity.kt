package com.example.smartdriver

import android.util.Log
import android.app.Activity
import android.content.*
import android.content.Context.RECEIVER_NOT_EXPORTED
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.smartdriver.databinding.ActivityMainBinding
import com.example.smartdriver.overlay.OverlayService
import java.text.Normalizer
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private lateinit var mediaProjectionLauncher: ActivityResultLauncher<Intent>
    private lateinit var sharedPreferences: SharedPreferences

    private var isRequestingProjection = false
    private var lastProjectionLaunchAt = 0L
    private val PROJECTION_LAUNCH_COOLDOWN_MS = 2500L

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

        // Chave para guardar a preferência do som
        internal const val KEY_SOUND_ENABLED = "sound_enabled"

        // [NOVO] Ações para Pausar/Retomar a captura enquanto estamos no menu
        const val ACTION_PAUSE_CAPTURE = "com.example.smartdriver.ACTION_PAUSE_CAPTURE"
        const val ACTION_RESUME_CAPTURE = "com.example.smartdriver.ACTION_RESUME_CAPTURE"

        private const val ONBOARDING_PREFS = "smartdriver_prefs"
        private const val KEY_ONBOARDING_DONE = "onboarding_done"
        private const val ONBOARDING_CLS = "com.example.smartdriver.permissions.OnboardingActivity"
        const val ACTION_SHUTDOWN_APP = "com.example.smartdriver.ACTION_SHUTDOWN_APP"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!isOnboardingDone() && classExists(ONBOARDING_CLS)) {
            val clazz = Class.forName(ONBOARDING_CLS) as Class<out Activity>
            startActivity(Intent(this, clazz))
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        runCatching {
            val tbId = resources.getIdentifier("toolbar", "id", packageName)
            if (tbId != 0) setSupportActionBar(findViewById(tbId))
        }

        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        setupResultLaunchers()
        setupButtonClickListeners()
        setupSwitches()

        hideLegacyPermissionUiIfPresent()
        hideUiByText("Permissões necessárias", "permissoes necessarias", "3. captura", "3 captura", "captura")

        val filter = IntentFilter(ACTION_SHUTDOWN_APP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(shutdownReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(shutdownReceiver, filter)
        }

        normalizeInitialSwitchState()

        // Verifica se fomos lançados para pedir permissão automática
        checkAutoRequestIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        checkAutoRequestIntent(intent)
    }

    private fun checkAutoRequestIntent(intent: Intent?) {
        if (intent?.getBooleanExtra("AUTO_REQUEST_PERMISSION", false) == true) {
            intent.removeExtra("AUTO_REQUEST_PERMISSION")
            binding.appStatusSwitch.isChecked = true
            requestScreenCapturePermission()
        }
    }

    override fun onResume() {
        super.onResume()

        // [NOVO] Estamos no menu, manda o serviço PAUSAR a análise para não dar falsos positivos
        sendBroadcast(Intent(ACTION_PAUSE_CAPTURE).setPackage(packageName))

        val savedActive = sharedPreferences.getBoolean(KEY_APP_ACTIVE, false)

        if (savedActive) {
            // Se o utilizador quer a app ativa, garantimos que o Overlay (botão) está visível
            // mesmo que a captura (token) tenha expirado.
            if (!OverlayService.isRunning.get()) {
                startOverlayService()
            }

            // Se a captura não está a correr, tentamos iniciar (vai falhar silenciosamente se não houver token, e tudo bem)
            if (!ScreenCaptureService.isRunning.get() && MediaProjectionData.isValid()) {
                startScreenCaptureService()
            }

            updateAppStatusUI(true)
        } else {
            updateAppStatusUI(false)
        }
    }

    override fun onPause() {
        super.onPause()
        // [NOVO] Saímos do menu (minimizámos ou fomos para outra app), manda o serviço RETOMAR
        sendBroadcast(Intent(ACTION_RESUME_CAPTURE).setPackage(packageName))
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(shutdownReceiver) }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_permissions_center -> {
                val clsName = "com.example.smartdriver.permissions.PermissionsCenterActivity"
                runCatching { Class.forName(clsName) as Class<out Activity> }
                    .onSuccess { startActivity(Intent(this, it)) }
                    .onFailure { Toast.makeText(this, "Indisponível.", Toast.LENGTH_SHORT).show() }
                true
            }
            R.id.action_shutdown -> {
                shutdownAppCompletely()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun shutdownAppCompletely() {
        stopScreenCaptureService()
        stopOverlayService()
        MediaProjectionData.clear()
        persistAppActive(false)
        binding.appStatusSwitch.isChecked = false
        updateAppStatusUI(false)
        finishAffinity()
    }

    private fun setupButtonClickListeners() {
        binding.settingsButton.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        binding.historyButton.setOnClickListener { startActivity(Intent(this, HistoryActivity::class.java)) }
        runCatching {
            val id = resources.getIdentifier("shutdownButton", "id", packageName)
            if (id != 0) findViewById<View>(id)?.visibility = View.GONE
        }
    }

    private fun setupResultLaunchers() {
        mediaProjectionLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                isRequestingProjection = false
                lastProjectionLaunchAt = SystemClock.elapsedRealtime()
                MediaProjectionData.clearConsentInFlight()

                if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                    MediaProjectionData.set(result.resultCode, result.data!!)
                    Toast.makeText(this, "Permissão concedida!", Toast.LENGTH_SHORT).show()

                    // Se o switch estiver ON, arrancamos tudo
                    if (binding.appStatusSwitch.isChecked) {
                        startServicesIfReady()
                        persistAppActive(true)
                        updateAppStatusUI(true)
                    }

                    // Manda explicitamente o token para o serviço
                    val i = Intent(this, ScreenCaptureService::class.java).apply {
                        putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                        putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
                    }
                    ContextCompat.startForegroundService(this, i)

                } else {
                    Toast.makeText(this, "Permissão de captura negada.", Toast.LENGTH_SHORT).show()
                    // Mantemos o overlay (botão) ativo se o switch estiver ON, mas a captura falha
                    if (!MediaProjectionData.isValid()) {
                        // Opcional: Desligar switch se for crítico
                        // binding.appStatusSwitch.isChecked = false
                    }
                }
            }
    }

    private fun setupSwitches() {
        // --- Switch Principal (Ativar/Desativar App) ---
        val savedActive = sharedPreferences.getBoolean(KEY_APP_ACTIVE, false)
        binding.appStatusSwitch.isChecked = savedActive
        updateAppStatusUI(savedActive)

        binding.appStatusSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (hasBasicPermissions()) {
                    // 1. Inicia o Overlay IMEDIATAMENTE (Botão Flutuante)
                    startOverlayService()
                    persistAppActive(true)
                    updateAppStatusUI(true)

                    // 2. Tenta iniciar captura. Se não tiver token, pede.
                    if (MediaProjectionData.isValid()) {
                        startScreenCaptureService()
                    } else {
                        requestScreenCapturePermission()
                    }
                } else {
                    checkPermissionsAndStartOrRequestCapture()
                }
            } else {
                // Desligar tudo
                stopScreenCaptureService()
                stopOverlayService()
                persistAppActive(false)
                updateAppStatusUI(false)
            }
        }

        // --- Switch de Sons do Semáforo ---
        val savedSound = sharedPreferences.getBoolean(KEY_SOUND_ENABLED, true)
        binding.soundStatusSwitch.isChecked = savedSound

        binding.soundStatusSwitch.setOnCheckedChangeListener { _, isChecked ->
            sharedPreferences.edit().putBoolean(KEY_SOUND_ENABLED, isChecked).apply()
        }
    }

    private fun normalizeInitialSwitchState() {
        val savedActive = sharedPreferences.getBoolean(KEY_APP_ACTIVE, false)
        if (savedActive && hasBasicPermissions()) {
            binding.appStatusSwitch.isChecked = true
            startServicesIfReady() // Tenta arrancar o que for possível
        } else {
            binding.appStatusSwitch.isChecked = false
            persistAppActive(false)
        }
        updateAppStatusUI(binding.appStatusSwitch.isChecked)
    }

    private fun checkPermissionsAndStartOrRequestCapture() {
        val now = SystemClock.elapsedRealtime()
        if (isRequestingProjection || MediaProjectionData.isConsentInFlight() || (now - lastProjectionLaunchAt) < PROJECTION_LAUNCH_COOLDOWN_MS) return

        val acc = isAccessibilityServiceEnabled()
        val ovl = Settings.canDrawOverlays(this)

        if (!acc) {
            Toast.makeText(this, "Ative o Serviço de Acessibilidade.", Toast.LENGTH_SHORT).show()
            binding.appStatusSwitch.isChecked = false
            return
        }
        if (!ovl) {
            Toast.makeText(this, "Permita a Sobreposição (Overlay).", Toast.LENGTH_SHORT).show()
            binding.appStatusSwitch.isChecked = false
            return
        }

        // Se chegou aqui, tem permissões básicas. Liga o Overlay.
        startOverlayService()
        persistAppActive(true)
        updateAppStatusUI(true)

        // Agora trata da captura
        if (!MediaProjectionData.isValid()) {
            requestScreenCapturePermission()
        } else {
            startScreenCaptureService()
        }
    }

    private fun requestScreenCapturePermission() {
        val now = SystemClock.elapsedRealtime()
        if (isRequestingProjection || MediaProjectionData.isConsentInFlight()) return
        if ((now - lastProjectionLaunchAt) < PROJECTION_LAUNCH_COOLDOWN_MS) return
        if (!MediaProjectionData.markConsentInFlight()) return

        try {
            isRequestingProjection = true
            mediaProjectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
        } catch (e: Exception) {
            isRequestingProjection = false
            MediaProjectionData.clearConsentInFlight()
            Toast.makeText(this, "Erro ao pedir permissão.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = "$packageName/${SmartDriverAccessibilityService::class.java.name}"
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        return enabled?.split(':')?.contains(expected) ?: false
    }

    private fun updateAppStatusUI(isActive: Boolean) {
        binding.appStatusText.text = getString(if (isActive) R.string.app_status_on else R.string.app_status_off)
        binding.appStatusText.setTextColor(ContextCompat.getColor(this, if (isActive) R.color.black else R.color.gray_inactive))
    }

    private fun hideLegacyPermissionUiIfPresent() {
        val ids = arrayOf("permissionsSection", "permissionsCard", "permissionsContainer", "permissionsHeaderText", "permissionsTitle", "accessibilityPermSwitch", "overlayPermSwitch", "accessibilityStatusTextView", "overlayStatusTextView", "captureStatusText", "saveImagesSwitch", "saveImagesLabel")
        ids.forEach { name -> val id = resources.getIdentifier(name, "id", packageName); if (id != 0) findViewById<View>(id)?.visibility = View.GONE }
    }

    private fun hideUiByText(vararg targetsRaw: String) {
        val targets = targetsRaw.map { normalize(it) }
        val root = findViewById<View>(android.R.id.content) as? ViewGroup ?: return
        fun shouldHide(text: CharSequence?): Boolean {
            if (text.isNullOrBlank()) return false
            val norm = normalize(text.toString())
            return targets.any { t -> norm.contains(t) }
        }
        fun hideRow(view: View) {
            var v: View? = view
            var container: ViewGroup? = null
            while (v != null) {
                val p = v.parent
                if (p is ViewGroup) { container = p; v = p } else break
            }
            (container ?: view).visibility = View.GONE
        }
        fun visit(v: View) {
            if (v is ViewGroup) { for (i in 0 until v.childCount) visit(v.getChildAt(i)) }
            else if (v is TextView) { if (shouldHide(v.text)) hideRow(v) }
        }
        visit(root)
    }

    private fun normalize(s: String): String = Normalizer.normalize(s.lowercase(Locale.getDefault()), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").replace(Regex("[^a-z0-9]+"), " ").trim()

    // --- Serviços ---

    // Verifica apenas as permissões "estáticas" (Acessibilidade e Overlay)
    private fun hasBasicPermissions(): Boolean {
        return isAccessibilityServiceEnabled() && Settings.canDrawOverlays(this)
    }

    // Tenta iniciar tudo o que for possível
    private fun startServicesIfReady(): Boolean {
        if (!hasBasicPermissions()) return false

        // Overlay arranca SEMPRE se tiver permissão de overlay
        val overlayOk = startOverlayService()

        // Captura arranca SÓ se tiver token
        var captureOk = false
        if (MediaProjectionData.isValid()) {
            captureOk = startScreenCaptureService()
        }

        return overlayOk
    }

    private fun startScreenCaptureService(): Boolean {
        if (!MediaProjectionData.isValid()) return false
        if (ScreenCaptureService.isRunning.get()) return true
        val intent = Intent(this, ScreenCaptureService::class.java).apply { putExtra(KEY_SAVE_IMAGES, false) }
        return try { ContextCompat.startForegroundService(this, intent); true } catch (_: Exception) { false }
    }

    private fun stopScreenCaptureService() {
        if (!ScreenCaptureService.isRunning.get()) return
        val intent = Intent(this, ScreenCaptureService::class.java).apply { action = ScreenCaptureService.ACTION_STOP_CAPTURE }
        runCatching { stopService(intent) }
    }

    private fun startOverlayService(): Boolean {
        if (!Settings.canDrawOverlays(this)) return false
        if (OverlayService.isRunning.get()) return true
        val intent = Intent(this, OverlayService::class.java)
        return try { ContextCompat.startForegroundService(this, intent); true } catch (_: Exception) { false }
    }

    private fun stopOverlayService() {
        if (!OverlayService.isRunning.get()) return
        val intent = Intent(this, OverlayService::class.java)
        runCatching { stopService(intent) }
    }

    private fun persistAppActive(active: Boolean) = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_APP_ACTIVE, active).apply()
    private fun isOnboardingDone(): Boolean = getSharedPreferences(ONBOARDING_PREFS, MODE_PRIVATE).getBoolean(KEY_ONBOARDING_DONE, false)
    private fun classExists(qualifiedName: String): Boolean = runCatching { Class.forName(qualifiedName) }.isSuccess
}