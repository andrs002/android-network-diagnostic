package com.andrs002.networkdiagnostic

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import androidx.appcompat.app.AppCompatActivity
import rikka.shizuku.Shizuku

/**
 * Shizuku requires an explicit user grant and an active shell service.
 * No background toggles are attempted unless the user enables this path.
 */
class ShizukuRecovery(
    private val activity: AppCompatActivity,
    private val log: (String) -> Unit
) {
    @Volatile var enabled: Boolean = false
        private set
    @Volatile private var remote: IDataRecovery? = null
    @Volatile private var wanted = false

    private val args = Shizuku.UserServiceArgs(
        ComponentName(activity.packageName, DataRecoveryService::class.java.name)
    ).daemon(false).processNameSuffix("data_recovery").debuggable(false).version(2)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            remote = IDataRecovery.Stub.asInterface(binder)
            enabled = wanted && binder.pingBinder()
            log(if (enabled) "SHIZUKU_SERVICE_READY" else "SHIZUKU_SERVICE_NOT_READY")
        }
        override fun onServiceDisconnected(name: ComponentName) {
            remote = null
            enabled = false
            log("SHIZUKU_SERVICE_DISCONNECTED")
        }
    }

    private val binderReceived = Shizuku.OnBinderReceivedListener {
        if (wanted) requestEnable()
    }
    private val binderDead = Shizuku.OnBinderDeadListener {
        enabled = false
        remote = null
        log("SHIZUKU_BINDER_DEAD")
    }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { requestCode, result ->
        if (requestCode == 4701) {
            if (result == PackageManager.PERMISSION_GRANTED) {
                log("SHIZUKU_PERMISSION_GRANTED")
                requestEnable()
            } else {
                wanted = false
                log("SHIZUKU_PERMISSION_DENIED")
            }
        }
    }

    init {
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
    }

    fun requestEnable() {
        wanted = true
        try {
            if (!Shizuku.pingBinder()) {
                log("SHIZUKU_NOT_RUNNING:INSTALL_START_AND_PAIR_FIRST")
                return
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                log("SHIZUKU_REQUESTING_PERMISSION")
                Shizuku.requestPermission(4701)
                return
            }
            Shizuku.bindUserService(args, connection)
            log("SHIZUKU_BINDING_SERVICE")
        } catch (e: Exception) {
            enabled = false
            log("SHIZUKU_BIND_FAILED:" + e.javaClass.simpleName + ":" + e.message)
        }
    }

    /** Command completion does NOT prove that the network has recovered. */
    fun cycle(): String {
        if (!enabled) return "SKIPPED_SHIZUKU_NOT_READY"
        val svc = remote ?: return "SKIPPED_NO_SHIZUKU_SERVICE"
        return try {
            svc.cycleMobileData()
        } catch (e: Exception) {
            "DATA_CYCLE_EXCEPTION:" + e.javaClass.simpleName + ":" + e.message
        }
    }

    fun close() {
        wanted = false
        enabled = false
        remote = null
        try { Shizuku.unbindUserService(args, connection, true) } catch (_: Exception) {}
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        Shizuku.removeRequestPermissionResultListener(permissionResult)
    }
}
