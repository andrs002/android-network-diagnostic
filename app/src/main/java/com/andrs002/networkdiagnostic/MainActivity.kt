package com.andrs002.networkdiagnostic

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.*
import android.os.Bundle
import android.telephony.*
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.*

class MainActivity : AppCompatActivity() {
    private lateinit var output: TextView
    private lateinit var runButton: Button
    private lateinit var stopButton: Button
    private val executor=Executors.newSingleThreadScheduledExecutor()
    private var monitor:ScheduledFuture<*>?=null
    private val log=StringBuilder()
    private var sample=0
    @Volatile private var netEvent="INIT"

    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        val d=resources.displayMetrics.density; val pad=(16*d).toInt()
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(pad,pad,pad,pad)}
        runButton=Button(this).apply{text="開始深度監測";textSize=18f;setTextColor(Color.WHITE);setBackgroundColor(Color.rgb(0,100,200));isAllCaps=false;layoutParams=LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,(72*d).toInt()).apply{bottomMargin=pad}}
        stopButton=Button(this).apply{text="停止監測";isAllCaps=false;isEnabled=false}
        output=TextView(this).apply{text="Network Diagnostic v5\n\n新增 ServiceState、data connection、TAC/CI/Band、Android network LOST/AVAILABLE。";textSize=14f;setTextIsSelectable(true)}
        box.addView(runButton);box.addView(stopButton);box.addView(output);setContentView(ScrollView(this).apply{addView(box)})
        runButton.setOnClickListener{permissionsAndRun()};stopButton.setOnClickListener{stopMonitor()}
    }

    private fun permissionsAndRun(){val p=arrayOf(Manifest.permission.READ_PHONE_STATE,Manifest.permission.ACCESS_FINE_LOCATION);val m=p.filter{ActivityCompat.checkSelfPermission(this,it)!=PackageManager.PERMISSION_GRANTED};if(m.isNotEmpty())ActivityCompat.requestPermissions(this,m.toTypedArray(),7)else startMonitor()}
    override fun onRequestPermissionsResult(r:Int,p:Array<out String>,g:IntArray){super.onRequestPermissionsResult(r,p,g);if(r==7&&g.all{it==PackageManager.PERMISSION_GRANTED})startMonitor()}

    private val callback=object:ConnectivityManager.NetworkCallback(){
        override fun onAvailable(network:Network){netEvent="AVAILABLE:${network}"}
        override fun onLost(network:Network){netEvent="LOST:${network}"}
        override fun onCapabilitiesChanged(network:Network,c:NetworkCapabilities){netEvent="CAP:${if(c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))"CELL" else if(c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))"WIFI" else "OTHER"}"}
    }

    private fun startMonitor(){
        monitor?.cancel(false);log.clear();sample=0;netEvent="START"
        val cm=getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        try{cm.unregisterNetworkCallback(callback)}catch(_:Exception){}
        cm.registerDefaultNetworkCallback(callback)
        log.append("=== Network Diagnostic v5 deep log ===\n")
        log.append("time,sample,transport,rat,service,dataState,netEvent,registered,pci,earfcn,band,tac,ci,rsrp,rsrq,rssi,sinr,https\n")
        runButton.isEnabled=false;stopButton.isEnabled=true
        monitor=executor.scheduleAtFixedRate({takeSample()},0,1,TimeUnit.SECONDS)
    }

    private fun takeSample(){
        val now=SimpleDateFormat("HH:mm:ss",Locale.US).format(Date());sample++
        var transport="NONE";var rat="?";var service="?";var dataState="?";var registered="false";var pci="";var earfcn="";var band="";var tac="";var ci="";var rsrp="";var rsrq="";var rssi="";var sinr="";var https="-"
        try{
            val cm=getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager;val caps=cm.getNetworkCapabilities(cm.activeNetwork)
            transport=when{caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)==true->"CELLULAR";caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true->"WIFI";else->"NONE"}
            val tm=getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            rat=when(tm.dataNetworkType){TelephonyManager.NETWORK_TYPE_LTE->"LTE";TelephonyManager.NETWORK_TYPE_NR->"NR";else->tm.dataNetworkType.toString()}
            dataState=when(tm.dataState){TelephonyManager.DATA_CONNECTED->"CONNECTED";TelephonyManager.DATA_CONNECTING->"CONNECTING";TelephonyManager.DATA_DISCONNECTED->"DISCONNECTED";TelephonyManager.DATA_SUSPENDED->"SUSPENDED";else->tm.dataState.toString()}
            service=try{tm.serviceState?.toString()?.replace(',',';')?.take(220)?:"null"}catch(e:Exception){"ERR:${e.javaClass.simpleName}"}
            val serving=tm.allCellInfo?.firstOrNull{it.isRegistered}
            when(serving){
                is CellInfoLte->{val s=serving.cellSignalStrength;val id=serving.cellIdentity;registered="true";pci=id.pci.toString();earfcn=id.earfcn.toString();band=try{id.bands.joinToString("+")}catch(_:Exception){""};tac=id.tac.toString();ci=id.ci.toString();rsrp=s.rsrp.toString();rsrq=s.rsrq.toString();rssi=s.rssi.toString();sinr=s.rssnr.toString()}
                is CellInfoNr->{val s=serving.cellSignalStrength as CellSignalStrengthNr;val id=serving.cellIdentity as CellIdentityNr;registered="true";pci=id.pci.toString();earfcn=id.nrarfcn.toString();band=try{id.bands.joinToString("+")}catch(_:Exception){""};tac=id.tac.toString();ci=id.nci.toString();rsrp=s.ssRsrp.toString();rsrq=s.ssRsrq.toString();sinr=s.ssSinr.toString()}
            }
            if(sample%5==0){val t=System.nanoTime();try{val h=URL("https://www.google.com/generate_204").openConnection() as HttpURLConnection;h.connectTimeout=4000;h.readTimeout=4000;h.useCaches=false;h.connect();https="${h.responseCode}:${(System.nanoTime()-t)/1_000_000}ms";h.disconnect()}catch(e:Exception){https="FAIL:${e.javaClass.simpleName}"}}
        }catch(e:Exception){https="ERR:${e.javaClass.simpleName}"}
        val event=netEvent.replace(',',';');log.append("$now,$sample,$transport,$rat,$service,$dataState,$event,$registered,$pci,$earfcn,$band,$tac,$ci,$rsrp,$rsrq,$rssi,$sinr,$https\n")
        val text=log.toString();runOnUiThread{output.text=text.takeLast(24000)}
    }

    private fun stopMonitor(){monitor?.cancel(false);monitor=null;try{(getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(callback)}catch(_:Exception){};runButton.isEnabled=true;stopButton.isEnabled=false;output.text=log.toString().takeLast(50000)}
    override fun onDestroy(){monitor?.cancel(true);try{(getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(callback)}catch(_:Exception){};executor.shutdownNow();super.onDestroy()}
}
