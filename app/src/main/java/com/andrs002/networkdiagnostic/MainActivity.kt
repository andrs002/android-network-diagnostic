package com.andrs002.networkdiagnostic

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellIdentityNr
import android.telephony.CellSignalStrengthNr
import android.telephony.TelephonyManager
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private lateinit var output: TextView
    private lateinit var runButton: Button
    private lateinit var stopButton: Button
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private var monitor: ScheduledFuture<*>? = null
    private val log = StringBuilder()
    private var sample = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        val pad = (16 * d).toInt()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad,pad,pad,pad) }
        runButton = Button(this).apply {
            text="開始連續監測"; textSize=18f; setTextColor(Color.WHITE); setBackgroundColor(Color.rgb(0,100,200)); isAllCaps=false
            layoutParams=LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,(72*d).toInt()).apply{bottomMargin=pad}
        }
        stopButton = Button(this).apply { text="停止監測"; textSize=17f; isAllCaps=false; isEnabled=false }
        output = TextView(this).apply { text="Network Diagnostic v4\n\n每秒記錄 LTE/5G serving cell、RSRP/RSRQ/SINR，並定期做 HTTPS 測試。請在室外開始，再拿著手機走進室內。"; textSize=15f; setTextIsSelectable(true) }
        box.addView(runButton); box.addView(stopButton); box.addView(output)
        setContentView(ScrollView(this).apply{addView(box)})
        runButton.setOnClickListener { permissionsAndRun() }
        stopButton.setOnClickListener { stopMonitor() }
    }

    private fun permissionsAndRun(){
        val p=arrayOf(Manifest.permission.READ_PHONE_STATE,Manifest.permission.ACCESS_FINE_LOCATION)
        val missing=p.filter{ActivityCompat.checkSelfPermission(this,it)!=PackageManager.PERMISSION_GRANTED}
        if(missing.isNotEmpty()) ActivityCompat.requestPermissions(this,missing.toTypedArray(),7) else startMonitor()
    }
    override fun onRequestPermissionsResult(r:Int,p:Array<out String>,g:IntArray){super.onRequestPermissionsResult(r,p,g);if(r==7&&g.all{it==PackageManager.PERMISSION_GRANTED})startMonitor()}

    private fun startMonitor(){
        monitor?.cancel(false); log.clear(); sample=0
        log.append("=== Network Diagnostic v4 continuous log ===\n")
        log.append("time,sample,transport,rat,registered,pci,earfcn,rsrp,rsrq,rssi,sinr,https\n")
        runButton.isEnabled=false; stopButton.isEnabled=true
        monitor=executor.scheduleAtFixedRate({ takeSample() },0,1,TimeUnit.SECONDS)
    }

    private fun takeSample(){
        val now=SimpleDateFormat("HH:mm:ss",Locale.US).format(Date()); sample++
        var transport="NONE"; var rat="?"; var registered="false"; var pci=""; var earfcn=""; var rsrp=""; var rsrq=""; var rssi=""; var sinr=""; var https="-"
        try{
            val cm=getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val c=cm.getNetworkCapabilities(cm.activeNetwork)
            transport=when{c?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)==true->"CELLULAR";c?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true->"WIFI";else->"NONE"}
            val tm=getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            rat=when(tm.dataNetworkType){TelephonyManager.NETWORK_TYPE_LTE->"LTE";TelephonyManager.NETWORK_TYPE_NR->"NR";else->tm.dataNetworkType.toString()}
            val serving=tm.allCellInfo?.firstOrNull{it.isRegistered}
            when(serving){
                is CellInfoLte->{val s=serving.cellSignalStrength;val id=serving.cellIdentity;registered="true";pci=id.pci.toString();earfcn=id.earfcn.toString();rsrp=s.rsrp.toString();rsrq=s.rsrq.toString();rssi=s.rssi.toString();sinr=s.rssnr.toString()}
                is CellInfoNr->{val s=serving.cellSignalStrength as CellSignalStrengthNr;val id=serving.cellIdentity as CellIdentityNr;registered="true";pci=id.pci.toString();earfcn=id.nrarfcn.toString();rsrp=s.ssRsrp.toString();rsrq=s.ssRsrq.toString();sinr=s.ssSinr.toString()}
            }
            if(sample%5==0){
                val t=System.nanoTime(); try{val h=URL("https://www.google.com/generate_204").openConnection() as HttpURLConnection;h.connectTimeout=4000;h.readTimeout=4000;h.useCaches=false;h.connect();https="${h.responseCode}:${(System.nanoTime()-t)/1_000_000}ms";h.disconnect()}catch(e:Exception){https="FAIL:${e.javaClass.simpleName}"}
            }
        }catch(e:Exception){https="ERR:${e.javaClass.simpleName}"}
        log.append("$now,$sample,$transport,$rat,$registered,$pci,$earfcn,$rsrp,$rsrq,$rssi,$sinr,$https\n")
        val text=log.toString(); runOnUiThread{output.text=text.takeLast(16000)}
    }

    private fun stopMonitor(){monitor?.cancel(false);monitor=null;runButton.isEnabled=true;stopButton.isEnabled=false;output.text=log.toString().takeLast(30000)}
    override fun onDestroy(){monitor?.cancel(true);executor.shutdownNow();super.onDestroy()}
}
