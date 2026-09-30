package com.andrs002.networkdiagnostic

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.telephony.TelephonyManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.net.InetAddress
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private lateinit var output: TextView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL; setPadding(32,32,32,32)}
        val run=Button(this).apply{text="開始完整網路診斷"}
        output=TextView(this).apply{text="此工具檢查 Android 可取得的行動網路、網路能力、DNS 與連線狀態。\n\n注意：一般 App 無法直接讀取 Samsung modem 韌體內部狀態或完整 CA 組合。"; textSize=16f}
        box.addView(run); box.addView(output)
        setContentView(ScrollView(this).apply{addView(box)})
        run.setOnClickListener { permissionsAndRun() }
    }
    private fun permissionsAndRun(){
        val p=arrayOf(Manifest.permission.READ_PHONE_STATE,Manifest.permission.ACCESS_FINE_LOCATION)
        if(p.any{ActivityCompat.checkSelfPermission(this,it)!=PackageManager.PERMISSION_GRANTED}) ActivityCompat.requestPermissions(this,p,7) else diagnose()
    }
    override fun onRequestPermissionsResult(r:Int,p:Array<out String>,g:IntArray){super.onRequestPermissionsResult(r,p,g); if(r==7) diagnose()}
    private fun diagnose(){
        val sb=StringBuilder("=== Android Network Diagnostic ===\n")
        val cm=getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val n=cm.activeNetwork; val c=cm.getNetworkCapabilities(n)
        sb.append("Active network: ").append(n).append('\n')
        sb.append("Transport: ").append(when { c?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)==true -> "CELLULAR"; c?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true -> "WIFI"; else -> "OTHER/NONE" }).append('\n')
        sb.append("Internet capability: ${c?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)}\n")
        sb.append("Validated: ${c?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}\n")
        sb.append("Not metered: ${c?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)}\n")
        val tm=getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        sb.append("Operator: ${tm.networkOperatorName}\n")
        sb.append("Data network type: ${typeName(tm.dataNetworkType)}\n")
        sb.append("Data enabled: ${tm.isDataEnabled}\n")
        try { sb.append("Signal: ${tm.signalStrength}\n") } catch(e:Exception){sb.append("Signal: unavailable (${e.javaClass.simpleName})\n")}
        val lp=cm.getLinkProperties(n)
        sb.append("DNS: ${lp?.dnsServers?.joinToString()}\n")
        sb.append("MTU: ${lp?.mtu}\n")
        output.text=sb.append("\nTesting DNS/connectivity…").toString()
        thread {
            val tests=listOf("google.com","github.com","api.github.com")
            for(h in tests){
                val t=System.nanoTime(); try { val a=InetAddress.getAllByName(h); val ms=(System.nanoTime()-t)/1_000_000; sb.append("\nDNS $h: OK ${ms}ms -> ${a.firstOrNull()?.hostAddress}") } catch(e:Exception){sb.append("\nDNS $h: FAIL ${e.javaClass.simpleName}: ${e.message}")}
            }
            runOnUiThread { output.text=sb.toString() }
        }
    }
    private fun typeName(t:Int)=when(t){TelephonyManager.NETWORK_TYPE_LTE->"LTE/4G"; TelephonyManager.NETWORK_TYPE_NR->"NR/5G"; TelephonyManager.NETWORK_TYPE_HSPAP->"HSPA+"; TelephonyManager.NETWORK_TYPE_UMTS->"UMTS/3G"; TelephonyManager.NETWORK_TYPE_EDGE->"EDGE"; TelephonyManager.NETWORK_TYPE_GPRS->"GPRS"; else->"type=$t"}
}
