package com.example.smartdriver

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

    // Guards anti-duplo consentimento
    private var isRequestingProjection = false
    private var lastProjectionLaunchAt = 0L
    private val PROJECTION_LAUNCH_COOLDOWN_MS = 2500L

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
        internal const val KEY_SAVE_IMAGES = "save_images" // funcionalidade descontinuada; enviar sempre false

        // Onboarding (wizard)
        private const val ONBOARDING_PREFS = "smartdriver_prefs"
        private const val KEY_ONBOARDING_DONE = "onboarding_done"
        private const val ONBOARDING_CLS = "com.example.smartdriver.permissions.OnboardingActivity"

        const val ACTION_SHUTDOWN_APP = "com.example.smartdriver.ACTION_SHUTDOWN_APP"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ► Se for a primeira execução e existir o wizard, abre-o e sai do Main
        if (!isOnboardingDone() && classExists(ONBOARDING_CLS)) {
            val clazz = Class.forName(ONBOARDING_CLS) as Class<out Activity>
            startActivity(Intent(this, clazz))
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Se existir Toolbar no layout, usa-a para mostrar o menu
        runCatching {
            val tbId = resources.getIdentifier("toolbar", "id", packageName)
            if (tbId != 0) setSupportActionBar(findViewById(tbId))
        }

        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        setupResultLaunchers()
        setupButtonClickListeners()
        setupSwitches()

        // Ecrã principal minimalista: remove qualquer vestígio antigo
        hideLegacyPermissionUiIfPresent()
        hideUiByText(
            "Permissões necessárias", "permissoes necessarias",
            "3. captura", "3 captura", "captura"
        )

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
        updateAppStatusUI(sharedPreferences.getBoolean(KEY_APP_ACTIVE, false))
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(shutdownReceiver) }
    }

    // ---------- MENU topo ----------
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
                    .onFailure { Toast.makeText(this, "Centro de Permissões indisponível nesta build.", Toast.LENGTH_SHORT).show() }
                true
            }
            R.id.action_shutdown -> {
                stopScreenCaptureService()
                stopOverlayService()
                MediaProjectionData.clear()
                persistAppActive(false)
                binding.appStatusSwitch.isChecked = false
                updateAppStatusUI(false)
                finishAffinity()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ---------- Botões ----------
    private fun setupButtonClickListeners() {
        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.historyButton.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        // Se existir botão antigo de “Encerrar”, esconde (agora está no menu)
        runCatching {
            val id = resources.getIdentifier("shutdownButton", "id", packageName)
            if (id != 0) findViewById<View>(id)?.visibility = View.GONE
        }
    }

    // ---------- Launchers ----------
    private fun setupResultLaunchers() {
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
                isRequestingProjection = false
                lastProjectionLaunchAt = SystemClock.elapsedRealtime()
            }
    }

    // ---------- Switch principal ----------
    private fun setupSwitches() {
        val savedActive = sharedPreferences.getBoolean(KEY_APP_ACTIVE, false)
        binding.appStatusSwitch.isChecked = savedActive
        updateAppStatusUI(savedActive)

        binding.appStatusSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (hasAllCorePermissions()) {
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
                updateAppStatusUI(false)
            }
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
            Toast.makeText(this, "Ative o Serviço de Acessibilidade nas Definições do sistema.", Toast.LENGTH_SHORT).show()
            binding.appStatusSwitch.isChecked = false
            persistAppActive(false)
            updateAppStatusUI(false); return
        }
        if (!ovl) {
            Toast.makeText(this, "Permita o Overlay nas Definições do sistema.", Toast.LENGTH_SHORT).show()
            binding.appStatusSwitch.isChecked = false
            persistAppActive(false)
            updateAppStatusUI(false); return
        }
        if (!cap) {
            Toast.makeText(this, "Permita a Captura de Ecrã quando solicitado.", Toast.LENGTH_SHORT).show()
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
        }
    }

    // ---------- UI ----------
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

    // Esconde controles/headers legados por ID (se existirem no layout)
    private fun hideLegacyPermissionUiIfPresent() {
        val ids = arrayOf(
            // Secção de permissões (título/contêiner)
            "permissionsSection", "permissionsCard", "permissionsContainer",
            "permissionsHeaderText", "permissionsTitle",
            // Switches e estados legados
            "accessibilityPermSwitch", "overlayPermSwitch",
            "accessibilityStatusTextView", "overlayStatusTextView", "captureStatusText",
            // Guardar capturas — remover do ecrã principal
            "saveImagesSwitch", "saveImagesLabel"
        )
        ids.forEach { name ->
            val id = resources.getIdentifier(name, "id", packageName)
            if (id != 0) findViewById<View>(id)?.visibility = View.GONE
        }
    }

    // Esconde por TEXTO (normaliza acentos e pontuação; oculta o container da linha)
    private fun hideUiByText(vararg targetsRaw: String) {
        val targets = targetsRaw.map { normalize(it) }
        val root = findViewById<View>(android.R.id.content) as? ViewGroup ?: return

        fun shouldHide(text: CharSequence?): Boolean {
            if (text.isNullOrBlank()) return false
            val norm = normalize(text.toString())
            return targets.any { t -> norm.contains(t) }
        }

        fun hideRow(view: View) {
            // Sobe e tenta ocultar o container da linha; fallback: o próprio TextView
            var v: View? = view
            var container: ViewGroup? = null
            while (v != null) {
                val p = v.parent
                if (p is ViewGroup) {
                    container = p
                    v = p
                } else break
            }
            (container ?: view).visibility = View.GONE
        }

        fun visit(v: View) {
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) visit(v.getChildAt(i))
            } else if (v is TextView) {
                if (shouldHide(v.text)) hideRow(v)
            }
        }
        visit(root)
    }

    private fun normalize(s: String): String {
        val tmp = Normalizer.normalize(s.lowercase(Locale.getDefault()), Normalizer.Form.NFD)
        return tmp.replace("\\p{M}+".toRegex(), "") // remove acentos
            .replace("[^a-z0-9]+".toRegex(), " ")   // remove pontuação/símbolos
            .trim()
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
            // Não guardamos em galeria (feature descontinuada)
            putExtra(KEY_SAVE_IMAGES, false)
        }
        return try {
            ContextCompat.startForegroundService(this, intent); true
        } catch (_: Exception) {
            Toast.makeText(this, "Falha a iniciar captura.", Toast.LENGTH_SHORT).show(); false
        }
    }

    private fun stopScreenCaptureService() {
        if (!ScreenCaptureService.isRunning.get()) return
        val intent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_STOP_CAPTURE
        }
        runCatching { stopService(intent) }
    }

    private fun startOverlayService(): Boolean {
        if (!Settings.canDrawOverlays(this)) return false
        if (OverlayService.isRunning.get()) return true
        val intent = Intent(this, OverlayService::class.java)
        return try {
            ContextCompat.startForegroundService(this, intent); true
        } catch (_: Exception) {
            Toast.makeText(this, "Falha a iniciar overlay.", Toast.LENGTH_SHORT).show(); false
        }
    }

    private fun stopOverlayService() {
        if (!OverlayService.isRunning.get()) return
        val intent = Intent(this, OverlayService::class.java)
        runCatching { stopService(intent) }
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

    private fun isOnboardingDone(): Boolean =
        getSharedPreferences(ONBOARDING_PREFS, MODE_PRIVATE).getBoolean(KEY_ONBOARDING_DONE, false)

    private fun classExists(qualifiedName: String): Boolean =
        runCatching { Class.forName(qualifiedName) }.isSuccess
}
