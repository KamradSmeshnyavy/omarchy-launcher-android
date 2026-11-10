package io.github.kamradsmeshnyavy.launcher.system

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import io.github.kamradsmeshnyavy.launcher.core.Look
import io.github.kamradsmeshnyavy.launcher.core.Sounds

/**
 * Звуки на события системы, как omarchy-sound на компьютере: зарядка, батарея села,
 * разблокировка, громкость, Bluetooth. Играет пакет лаунчера, пока жив его процесс (он —
 * домашний экран, так что почти всегда). Свой звук системы (если он есть) не подменить
 * без root — он прозвучит тоже; поэтому каждое событие — свой выключатель.
 */
object SoundEvents {
    /** Событие → ключ выключателя и звук пакета. */
    enum class Kind(val key: String, val default: Boolean) {
        CHARGE("evCharge", true),
        BATTERY("evBattery", true),
        UNLOCK("evUnlock", false),
        VOLUME("evVolume", false),
        BLUETOOTH("evBluetooth", true),
    }

    private const val VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
    private var lastVolume = 0L
    private var registered = false

    fun enabled(context: Context, kind: Kind): Boolean = Look.prefs(context).getBoolean(kind.key, kind.default)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val (kind, event) = when (intent.action) {
                Intent.ACTION_POWER_CONNECTED -> Kind.CHARGE to Sounds.Event.CHARGE
                Intent.ACTION_POWER_DISCONNECTED -> Kind.CHARGE to Sounds.Event.CLOSE
                Intent.ACTION_BATTERY_LOW -> Kind.BATTERY to Sounds.Event.CRITICAL
                Intent.ACTION_USER_PRESENT -> Kind.UNLOCK to Sounds.Event.LOCK
                BluetoothDevice.ACTION_ACL_CONNECTED -> Kind.BLUETOOTH to Sounds.Event.SWITCH
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> Kind.BLUETOOTH to Sounds.Event.CLOSE
                VOLUME_CHANGED -> {
                    // Только музыка/звонок, и не чаще раза в 80 мс (зажатая клавиша).
                    val now = System.currentTimeMillis()
                    if (now - lastVolume < 80) return
                    lastVolume = now
                    Kind.VOLUME to Sounds.Event.VOLUME
                }
                else -> return
            }
            if (!enabled(context, kind)) return
            Sounds.play(context, event)
        }
    }

    /** Из Application.onCreate: подписка на системные события на всё время жизни процесса. */
    fun start(context: Context) {
        if (registered) return
        registered = true
        val app = context.applicationContext
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_LOW)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(VOLUME_CHANGED)
            // Подключения Bluetooth приходят только с разрешением «Устройства поблизости».
            if (app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            }
        }
        // Системные рассылки доходят и до неэкспортированного приёмника; чужие приложения — нет.
        app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
    }

    /** Разрешение Bluetooth выдали позже: переподписаться, чтобы ловить и его. */
    fun restart(context: Context) {
        if (!registered) return start(context)
        runCatching { context.applicationContext.unregisterReceiver(receiver) }
        registered = false
        start(context)
    }
}
