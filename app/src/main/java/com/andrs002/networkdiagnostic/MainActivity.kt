package com.andrs002.networkdiagnostic

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellIdentityNr
import android.telephony.CellSignalStrengthNr
import android.telephony.TelephonyManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private lateinit var output: TextView
    private lateinit var runButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32) }
        runButton = Button(this).apply { text = "開始完整網路診斷" }
        output = TextView(this).apply {
            text = "Network Diagnostic v2\n\n按上方按鈕開始。會檢查目前傳輸、Android 可見的 LTE/5G 訊號、DNS 與 HTTPS 連線。\n\n一般 App 無法直接讀取 Samsung modem 韌體內部狀態或完整 CA 組合。"
            textSize = 16f; setTextIsSelectable(true)
        }
        box.addView(runButton); box.addView(output)
        setContentView(ScrollView(this).apply { addView(box) })
        runButton.setOnClickListener { permissionsAndRun() }
    }

    private fun permissionsAndRun() {
        val permissions = arrayOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.ACCESS_FINE_LOCATION)
        val missing = permissions.filter { ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            output.text = "Network Diagnostic v2\n\n等待權限：${missing.joinToString()}\n允許後會自動開始診斷。"
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 7)
        } else diagnose()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 7) diagnose()
    }

    private fun diagnose() {
        runButton.isEnabled = false
        val sb = StringBuilder("=== Network Diagnostic v2 ===\n")
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val n = cm.activeNetwork
            val c = cm.getNetworkCapabilities(n)
            sb.append("Active network: $n\n")
            sb.append("Transport: ").append(when {
                c?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "CELLULAR"
                c?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "WIFI"
                else -> "OTHER/NONE"
            }).append('\n')
            sb.append("Internet capability: ${c?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)}\n")
            sb.append("Validated: ${c?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}\n")
            sb.append("Metered: ${c?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true}\n")
            val lp = cm.getLinkProperties(n)
            sb.append("DNS servers: ${lp?.dnsServers?.joinToString()}\n")
            sb.append("MTU: ${lp?.mtu}\n")

            val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            sb.append("Operator: ${tm.networkOperatorName}\n")
            try { sb.append("Data network: ${typeName(tm.dataNetworkType)}\n") } catch (e: Exception) { sb.append("Data network: unavailable (${e.javaClass.simpleName})\n") }
            try { sb.append("Data enabled: ${tm.isDataEnabled}\n") } catch (e: Exception) { sb.append("Data enabled: unavailable\n") }
            try { sb.append("Signal summary: ${tm.signalStrength}\n") } catch (e: Exception) { sb.append("Signal summary: unavailable (${e.javaClass.simpleName})\n") }

            sb.append("\n--- Visible/serving cells ---\n")
            try {
                val cells = tm.allCellInfo
                if (cells.isNullOrEmpty()) sb.append("No cell info returned. Check location permission/location service.\n")
                else cells.forEach { cell ->
                    when (cell) {
                        is CellInfoLte -> {
                            val s = cell.cellSignalStrength
                            val id = cell.cellIdentity
                            sb.append("LTE registered=${cell.isRegistered} pci=${id.pci} earfcn=${id.earfcn} rsrp=${s.rsrp} rsrq=${s.rsrq} rssi=${s.rssi} rssnr=${s.rssnr}\n")
                        }
                        is CellInfoNr -> {
                            val s = cell.cellSignalStrength as CellSignalStrengthNr
                            val id = cell.cellIdentity as CellIdentityNr
                            sb.append("NR registered=${cell.isRegistered} pci=${id.pci} nrarfcn=${id.nrarfcn} ssRsrp=${s.ssRsrp} ssRsrq=${s.ssRsrq} ssSinr=${s.ssSinr}\n")
                        }
                        else -> sb.append("${cell.javaClass.simpleName} registered=${cell.isRegistered}\n")
                    }
                }
            } catch (e: Exception) { sb.append("Cell info error: ${e.javaClass.simpleName}: ${e.message}\n") }

            sb.append("\n--- DNS / HTTPS tests ---\n")
            output.text = sb.append("Running...\n").toString()
            thread {
                listOf("google.com", "github.com", "cloudflare.com").forEach { host ->
                    val start = System.nanoTime()
                    try {
                        val addresses = InetAddress.getAllByName(host)
                        val ms = (System.nanoTime() - start) / 1_000_000
                        sb.append("DNS $host: OK ${ms}ms -> ${addresses.firstOrNull()?.hostAddress}\n")
                    } catch (e: Exception) { sb.append("DNS $host: FAIL ${e.javaClass.simpleName}: ${e.message}\n") }
                }
                listOf("https://www.google.com/generate_204", "https://www.cloudflare.com/cdn-cgi/trace").forEach { address ->
                    val start = System.nanoTime()
                    try {
                        val conn = URL(address).openConnection() as HttpURLConnection
                        conn.connectTimeout = 7000; conn.readTimeout = 7000; conn.instanceFollowRedirects = false; conn.connect()
                        val code = conn.responseCode
                        val ms = (System.nanoTime() - start) / 1_000_000
                        sb.append("HTTPS $address: HTTP $code in ${ms}ms\n"); conn.disconnect()
                    } catch (e: Exception) { sb.append("HTTPS $address: FAIL ${e.javaClass.simpleName}: ${e.message}\n") }
                }
                runOnUiThread { output.text = sb.toString(); runButton.isEnabled = true; runButton.text = "重新診斷" }
            }
        } catch (e: Exception) {
            output.text = sb.append("\nFATAL: ${e.javaClass.name}: ${e.message}\n").toString(); runButton.isEnabled = true
        }
    }

    private fun typeName(t: Int) = when (t) {
        TelephonyManager.NETWORK_TYPE_LTE -> "LTE/4G"
        TelephonyManager.NETWORK_TYPE_NR -> "NR/5G"
        TelephonyManager.NETWORK_TYPE_HSPAP -> "HSPA+"
        TelephonyManager.NETWORK_TYPE_UMTS -> "UMTS/3G"
        TelephonyManager.NETWORK_TYPE_EDGE -> "EDGE"
        TelephonyManager.NETWORK_TYPE_GPRS -> "GPRS"
        else -> "type=$t"
    }
}
