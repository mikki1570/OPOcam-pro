package com.my.remotewebcam

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("cam_prefs", Context.MODE_PRIVATE)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#121212"))
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        val title = TextView(this).apply {
            text = "🎥 Remote Webcam PRO"
            textSize = 24f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 20)
        }

        val ipText = TextView(this).apply {
            text = "Адреса для входа (Tailscale / Wi-Fi):\n" + getDeviceIpAddresses()
            textSize = 15f
            setTextColor(Color.parseColor("#4CAF50"))
            setPadding(0, 0, 0, 24)
        }

        val userInput = EditText(this).apply {
            hint = "Логин для входа (по умолч. admin)"
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            setText(prefs.getString("auth_user", "admin"))
        }

        val passInput = EditText(this).apply {
            hint = "Пароль (оставь пустым, чтобы без пароля)"
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            setText(prefs.getString("auth_pass", "1234"))
        }

        val tokenInput = EditText(this).apply {
            hint = "Telegram Bot Token"
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            setText(prefs.getString("tg_token", ""))
        }

        val chatInput = EditText(this).apply {
            hint = "Telegram Chat ID"
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            setText(prefs.getString("tg_chat", ""))
        }

        val autoBootCheck = CheckBox(this).apply {
            text = "Запускать автоматически при включении телефона 🔄"
            setTextColor(Color.WHITE)
            isChecked = prefs.getBoolean("auto_boot", true)
        }

        val btnStart = Button(this).apply {
            text = "▶️ СОХРАНИТЬ И ЗАПУСТИТЬ СЕРВЕР (:8080)"
            setBackgroundColor(Color.parseColor("#2E7D32"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                prefs.edit()
                    .putString("auth_user", userInput.text.toString().trim().ifEmpty { "admin" })
                    .putString("auth_pass", passInput.text.toString().trim())
                    .putString("tg_token", tokenInput.text.toString().trim())
                    .putString("tg_chat", chatInput.text.toString().trim())
                    .putBoolean("auto_boot", autoBootCheck.isChecked)
                    .apply()
                if (hasPermissions()) {
                    startCamService()
                } else {
                    requestPerms()
                }
            }
        }

        val btnStop = Button(this).apply {
            text = "⏹ ОСТАНОВИТЬ СЕРВЕР"
            setBackgroundColor(Color.parseColor("#C62828"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                stopService(Intent(this@MainActivity, CameraStreamService::class.java))
                Toast.makeText(this@MainActivity, "Сервер остановлен ⏹", Toast.LENGTH_SHORT).show()
            }
        }

        layout.addView(title)
        layout.addView(ipText)
        layout.addView(userInput)
        layout.addView(passInput)
        layout.addView(tokenInput)
        layout.addView(chatInput)
        layout.addView(autoBootCheck)
        layout.addView(Space(this).apply { minimumHeight = 20 })
        layout.addView(btnStart)
        layout.addView(Space(this).apply { minimumHeight = 20 })
        layout.addView(btnStop)

        scroll.addView(layout)
        setContentView(scroll)
        if (!hasPermissions()) requestPerms()
    }

    private fun startCamService() {
        val intent = Intent(this, CameraStreamService::class.java)
        ContextCompat.startForegroundService(this, intent)
        Toast.makeText(this, "Сервер PRO запущен! Можно блокировать экран 📱🌙", Toast.LENGTH_LONG).show()
    }

    private fun hasPermissions(): Boolean {
        val perms = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return perms.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun requestPerms() {
        val perms = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        ActivityCompat.requestPermissions(this, perms.toTypedArray(), 101)
    }

    private fun getDeviceIpAddresses(): String {
        val list = mutableListOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        list.add("• http://${addr.hostAddress}:8080 (${iface.displayName})")
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return if (list.isEmpty()) "Сеть не найдена" else list.joinToString("\n")
    }
}
