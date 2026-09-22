package com.dmcore.core

import android.Manifest
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.app.AlarmManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.dmcore.core.overlay.OverlayService
import com.dmcore.core.service.CoreForegroundService

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView

    private val runtimePermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            requestOverlayPermissionIfNeeded()
        }

    private val overlaySettingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            requestBatteryExemptionIfNeeded()
        }

    private val batterySettingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            requestExactAlarmPermissionIfNeeded()
        }

    private val exactAlarmSettingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            updateStatus()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)

        findViewById<Button>(R.id.requestPermissionsButton).setOnClickListener {
            requestRuntimePermissions()
        }
        findViewById<Button>(R.id.startListeningButton).setOnClickListener {
            ContextCompat.startForegroundService(this, Intent(this, CoreForegroundService::class.java))
            updateStatus()
        }
        val testInput = findViewById<EditText>(R.id.testInput)
        findViewById<Button>(R.id.showOverlayButton).setOnClickListener {
            val text = testInput.text.toString().trim().ifEmpty {
                "Hola, soy C.O.R.E. Esto es una prueba del overlay."
            }
            val intent = Intent(this, OverlayService::class.java).apply {
                putExtra(OverlayService.EXTRA_TEST_TEXT, text)
            }
            startService(intent)
        }

        animateOrb()
    }

    private fun animateOrb() {
        // Punto orbitando: velocidad angular constante, por eso sí lineal.
        ObjectAnimator.ofFloat(findViewById(R.id.orbOrbit), View.ROTATION, 0f, 360f).apply {
            duration = 9_000
            repeatCount = ObjectAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }

        // Anillo externo girando muy despacio en sentido contrario: da profundidad
        // (paralaje) en vez de que todo se mueva junto como una sola pieza.
        ObjectAnimator.ofFloat(findViewById(R.id.orbRingOuter), View.ROTATION, 360f, 0f).apply {
            duration = 26_000
            repeatCount = ObjectAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
        ObjectAnimator.ofFloat(findViewById(R.id.orbRingInner), View.ROTATION, 0f, -360f).apply {
            duration = 34_000
            repeatCount = ObjectAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }

        // Núcleo "respirando": crece/brilla y vuelve a bajar, curva suave (no lineal).
        val glow = findViewById<View>(R.id.orbCoreGlow)
        AnimatorSet().apply {
            val scale = PropertyValuesHolder.ofFloat(View.SCALE_X, 0.7f, 1.35f)
            val scaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.7f, 1.35f)
            val alpha = PropertyValuesHolder.ofFloat(View.ALPHA, 0.55f, 1f)
            val pulse = ObjectAnimator.ofPropertyValuesHolder(glow, scale, scaleY, alpha).apply {
                duration = 2_600
                repeatCount = ObjectAnimator.INFINITE
                repeatMode = ObjectAnimator.REVERSE
                interpolator = AccelerateDecelerateInterpolator()
            }
            play(pulse)
            start()
        }

        // Toda la esfera respira un poquito también, desfasada del núcleo, para que
        // no se sienta como una sola animación repetida en dos capas.
        val sphere = findViewById<View>(R.id.orbNeuralNet)
        ObjectAnimator.ofPropertyValuesHolder(
            sphere,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.035f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.035f),
        ).apply {
            duration = 3_400
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            startDelay = 400
            start()
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun requestRuntimePermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        runtimePermissionsLauncher.launch(permissions.toTypedArray())
    }

    private fun requestOverlayPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"),
            )
            overlaySettingsLauncher.launch(intent)
        } else {
            requestBatteryExemptionIfNeeded()
        }
    }

    private fun requestBatteryExemptionIfNeeded() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName"),
            )
            batterySettingsLauncher.launch(intent)
        } else {
            requestExactAlarmPermissionIfNeeded()
        }
    }

    private fun requestExactAlarmPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            updateStatus()
            return
        }
        val alarmManager = getSystemService(AlarmManager::class.java)
        if (!alarmManager.canScheduleExactAlarms()) {
            val intent = Intent(
                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                Uri.parse("package:$packageName"),
            )
            exactAlarmSettingsLauncher.launch(intent)
        } else {
            updateStatus()
        }
    }

    private fun hasAllPermissions(): Boolean {
        val micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val overlayGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
        val notificationsGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val exactAlarmGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        return micGranted && overlayGranted && notificationsGranted && exactAlarmGranted
    }

    private fun updateStatus() {
        statusText.text = if (hasAllPermissions()) {
            getString(R.string.status_ready)
        } else {
            getString(R.string.status_missing_permissions)
        }
    }
}
