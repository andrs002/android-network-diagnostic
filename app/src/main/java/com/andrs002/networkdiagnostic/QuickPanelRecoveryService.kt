package com.andrs002.networkdiagnostic

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Explicitly user-triggered One UI / Android Quick Settings recovery experiment.
 *
 * Does NOT need root, ADB, Shizuku or Wi-Fi, but requires the user to enable
 * this accessibility service and to expose the Mobile data Quick Settings tile.
 * No automatic/background data cycling: Samsung's tile UI must first be tested.
 *
 * Nothing is toggled unless the tile is found by an accessible label.
 * After disabling data we always attempt to turn it back ON.
 */
class QuickPanelRecoveryService : AccessibilityService() {
    companion object {
        @Volatile var active: QuickPanelRecoveryService? = null
            private set
    }

    private enum class Phase { IDLE, TURN_OFF, TURN_ON }
    private val main = Handler(Looper.getMainLooper())
    private var phase = Phase.IDLE
    private var finishCallback: ((String) -> Unit)? = null
    private var lastAttemptAt = 0L
    private var usedSwipe = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        active = this
    }

    override fun onDestroy() {
        active = null
        if (phase != Phase.IDLE) finish("SERVICE_STOPPED_CHECK_DATA_MANUALLY", false)
        super.onDestroy()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        active = null
        if (phase != Phase.IDLE) finish("SERVICE_UNBOUND_CHECK_DATA_MANUALLY", false)
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Never scrape other apps' content. Read SystemUI only during a manual
        // recovery request, using the one-time lookup in findMobileDataTile().
    }

    override fun onInterrupt() {}

    fun requestManualCycle(callback: (String) -> Unit) {
        main.post {
            if (phase != Phase.IDLE) {
                callback("UI_RECOVERY_BUSY")
                return@post
            }
            if (System.currentTimeMillis() - lastAttemptAt < 12000L) {
                callback("UI_RECOVERY_COOLDOWN_12_SECONDS")
                return@post
            }
            val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val inCall = try { tm.callState != TelephonyManager.CALL_STATE_IDLE }
                catch (_: Exception) { true }
            if (inCall) {
                callback("UI_RECOVERY_BLOCKED_DURING_CALL")
                return@post
            }
            val enabled = dataEnabled()
            if (enabled == null) {
                callback("UI_RECOVERY_CANNOT_READ_DATA_STATE")
                return@post
            }
            finishCallback = callback
            phase = if (enabled) Phase.TURN_OFF else Phase.TURN_ON
            lastAttemptAt = System.currentTimeMillis()
            usedSwipe = false
            showQuickPanelAndTap()
        }
    }

    private fun dataEnabled(): Boolean? =
        try {
            (getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager).isDataEnabled
        } catch (_: Exception) {
            null
        }

    private fun showQuickPanelAndTap() {
        val opened = performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
        if (!opened) {
            // Some One UI releases don't expose GLOBAL_ACTION_QUICK_SETTINGS.
            swipeFromTopRight()
            main.postDelayed({ clickTileOrFallback() }, 850L)
        } else {
            main.postDelayed({ clickTileOrFallback() }, 850L)
        }
    }

    private fun clickTileOrFallback() {
        if (phase == Phase.IDLE) return
        val tile = findMobileDataTile()
        if (tile == null) {
            if (!usedSwipe) {
                usedSwipe = true
                swipeFromTopRight()
                main.postDelayed({ clickTileOrFallback() }, 850L)
            } else {
                finish(
                    if (phase == Phase.TURN_ON)
                        "DATA_MAY_BE_OFF_TILE_NOT_FOUND_TURN_ON_MANUALLY"
                    else "MOBILE_DATA_TILE_NOT_FOUND_NO_CHANGES",
                    false
                )
            }
            return
        }
        val target = clickableTile(tile)
        if (target == null || !target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            finish(
                if (phase == Phase.TURN_ON)
                    "DATA_MAY_BE_OFF_TILE_NOT_CLICKABLE_TURN_ON_MANUALLY"
                else "MOBILE_DATA_TILE_NOT_CLICKABLE_NO_CHANGES",
                false
            )
            return
        }
        val expectingEnabled = phase == Phase.TURN_ON
        waitForDataState(expectingEnabled, 0)
    }

    private fun waitForDataState(expectEnabled: Boolean, retry: Int) {
        main.postDelayed({
            if (phase == Phase.IDLE) return@postDelayed
            val state = dataEnabled()
            if (state == expectEnabled) {
                if (expectEnabled) {
                    // A successful UI click doesn't imply actual Internet access.
                    finish("UI_DATA_ENABLED_VERIFY_INTERNET_SEPARATELY", true)
                } else {
                    phase = Phase.TURN_ON
                    usedSwipe = false
                    main.postDelayed({ showQuickPanelAndTap() }, 1300L)
                }
            } else if (retry < 6) {
                waitForDataState(expectEnabled, retry + 1)
            } else {
                finish(
                    if (expectEnabled)
                        "DATA_STILL_DISABLED_TURN_ON_MANUALLY"
                    else "DATA_OFF_NOT_CONFIRMED_NO_RETRY",
                    false
                )
            }
        }, 450L)
    }

    private fun finish(result: String, canClosePanel: Boolean) {
        val cb = finishCallback
        finishCallback = null
        phase = Phase.IDLE
        usedSwipe = false
        if (canClosePanel) performGlobalAction(GLOBAL_ACTION_BACK)
        cb?.invoke(result)
    }

    private fun clickableTile(label: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var node: AccessibilityNodeInfo? = label
        repeat(4) {
            if (node?.isClickable == true && node?.isEnabled == true) return node
            node = node?.parent
        }
        return null
    }

    private fun findMobileDataTile(): AccessibilityNodeInfo? {
        val rootNodes = ArrayList<AccessibilityNodeInfo>()
        val current = rootInActiveWindow
        if (current != null) rootNodes.add(current)
        for (window in windows) {
            if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION &&
                window.type != AccessibilityWindowInfo.TYPE_SYSTEM) continue
            window.root?.let { rootNodes.add(it) }
        }
        for (root in rootNodes) {
            if (root.packageName?.toString() != "com.android.systemui") continue
            val found = searchNode(root, 0)
            if (found != null) return found
        }
        return null
    }

    private fun searchNode(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
        if (depth > 20 || !node.isVisibleToUser) return null
        val label = (node.text?.toString().orEmpty() + " " +
            node.contentDescription?.toString().orEmpty()).lowercase()
        val valid = label.contains("行動數據") ||
            label.contains("行動資料") ||
            label.contains("mobile data") ||
            label.contains("移动数据") ||
            label.contains("모바일 데이터")
        if (valid && clickableTile(node) != null) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            searchNode(child, depth + 1)?.let { return it }
        }
        return null
    }

    private fun swipeFromTopRight() {
        val metrics = resources.displayMetrics
        val x = metrics.widthPixels * 0.90f
        val path = Path().apply {
            moveTo(x, metrics.heightPixels * 0.04f)
            lineTo(x, metrics.heightPixels * 0.64f)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 450L))
            .build()
        dispatchGesture(gesture, null, null)
    }
}
