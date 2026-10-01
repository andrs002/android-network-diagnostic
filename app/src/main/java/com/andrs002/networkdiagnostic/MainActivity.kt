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
import java.net.InetAddress
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

    private fun now()=SimpleDateFormat("HH:mm:ss.SSS",Locale.US).format(Date())
    @Synchronized private fun appendLine(s:String){log.append(s).append('\n')}
    private fun callbackEvent(s:String){netEvent=s;appendLine("EVENT,${now()},${s.replace(',',';')}")}

    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        val d=resources.displayMetrics.density;val pad=(16*d).toInt()
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(pad,pad,pad,pad)}
        runButton=Button(this).apply{text="開始深度監測 v6";textSize=18f;setTextColor(Color.WHITE);setBackgroundColor(Color.rgb(0,100,200));isAllCaps=false;layoutParams=LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,(72*d).toInt()).apply{bottomMargin=pad}}
        stopButton=Button(this).apply{text="停止監測";isAllCaps=false;isEnabled=false}
        output=TextView(this).apply{text="Network Diagnostic v6\n\n完整保存 NetworkCallback 時間線，並分開測 DNS 與 HTTPS。";textSize=14f;setTextIsSelectable(true)}
        box.addView(runButton);box.addView(stopButton);box.addView(output);setContentView(ScrollView(this).apply{addView(box)})
        runButton.setOnClickListener{permissionsAndRun()};stopButton.setOnClickListener{stopMonitor()}
    }

    private fun permissionsAndRun(){val p=arrayOf(Manifest.permission.READ_PHONE_STATE,Manifest.permission.ACCESS_FINE_LOCATION);val m=p.filter{ActivityCompat.checkSelfPermission(this,it)!=PackageManager.PERMISSION_GRANTED};if(m.isNotEmpty())ActivityCompat.requestPermissions(this,m.toTypedArray(),7)else startMonitor()}
    override fun onRequestPermissionsResult(r:Int,p:Array<out String>,g:IntArray){super.onRequestPermissionsResult(r,p,g);if(r==7&&g.all{it==PackageManager.PERMISSION_GRANTED})startMonitor()}

    private val callback=object:ConnectivityManager.NetworkCallback(){
        override fun onAvailable(n:Network)=callbackEvent("AVAILABLE:$n")
        override fun onLost(n:Network)=callbackEvent("LOST:$n")
        override fun onLosing(n:Network,maxMs:Int)=callbackEvent("LOSING:$n:$maxMs")
        override fun onUnavailable()=callbackEvent("UNAVAILABLE")
        override fun onCapabilitiesChanged(n:Network,c:NetworkCapabilities){callbackEvent("CAP:$n:${if(c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))"CELL" else if(c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))"WIFI" else "OTHER"}:VALID=${c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}")}
        override fun onLinkPropertiesChanged(n:Network,lp:LinkProperties)=callbackEvent("LINK:$n:DNS=${lp.dnsServers.joinToString("+")}:IF=${lp.interfaceName}")
    }

    private fun startMonitor(){
        monitor?.cancel(false);synchronized(this){log.clear()};sample=0;netEvent="START"
        val cm=getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        try{cm.unregisterNetworkCallback(callback)}catch(_:Exception){}
        cm.registerDefaultNetworkCallback(callback)
        appendLine("=== Network Diagnostic v6 deep log ===")
        appendLine("SAMPLE,time,n,transport,rat,voiceReg,dataReg,dataState,lastEvent,registered,pci,earfcn,band,tac,ci,rsrp,rsrq,rssi,sinr,dns,https")
        runButton.isEnabled=false;stopButton.isEnabled=true
        monitor=executor.scheduleAtFixedRate({takeSample()},0,1,TimeUnit.SECONDS)
    }

    private fun takeSample(){
        sample++;var transport="NONE";var rat="?";var voice="?";var data="?";var dataState="?";var registered="false";var pci="";var earfcn="";var band="";var tac="";var ci="";var rsrp="";var rsrq="";var rssi="";var sinr="";var dns="-";var https="-"
        try{
            val cm=getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager;val caps=cm.getNetworkCapabilities(cm.activeNetwork)
            transport=when{caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)==true->"CELLULAR";caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true->"WIFI";else->"NONE"}
            val tm=getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            rat=when(tm.dataNetworkType){TelephonyManager.NETWORK_TYPE_LTE->"LTE";TelephonyManager.NETWORK_TYPE_NR->"NR";else->tm.dataNetworkType.toString()}
            val ss=tm.serviceState
            voice=when(ss?.state){ServiceState.STATE_IN_SERVICE->"IN";ServiceState.STATE_OUT_OF_SERVICE->"OUT";ServiceState.STATE_EMERGENCY_ONLY->"EMERGENCY";ServiceState.STATE_POWER_OFF->"OFF";else->"?"}
            data=try{if(ss?.dataRegistrationState==ServiceState.STATE_IN_SERVICE)"IN" else if(ss?.dataRegistrationState==ServiceState.STATE_OUT_OF_SERVICE)"OUT" else ss?.dataRegistrationState.toString()}catch(_:Exception){"?"}
            dataState=when(tm.dataState){TelephonyManager.DATA_CONNECTED->"CONNECTED";TelephonyManager.DATA_CONNECTING->"CONNECTING";TelephonyManager.DATA_DISCONNECTED->"DISCONNECTED";TelephonyManager.DATA_SUSPENDED->"SUSPENDED";else->tm.dataState.toString()}
            val serving=tm.allCellInfo?.firstOrNull{it.isRegistered}
            when(serving){
                is CellInfoLte->{val s=serving.cellSignalStrength;val id=serving.cellIdentity;registered="true";pci=id.pci.toString();earfcn=id.earfcn.toString();band=try{id.bands.joinToString("+")}catch(_:Exception){""};tac=id.tac.toString();ci=id.ci.toString();rsrp=s.rsrp.toString();rsrq=s.rsrq.toString();rssi=s.rssi.toString();sinr=s.rssnr.toString()}
                is CellInfoNr->{val s=serving.cellSignalStrength as CellSignalStrengthNr;val id=serving.cellIdentity as CellIdentityNr;registered="true";pci=id.pci.toString();earfcn=id.nrarfcn.toString();band=try{id.bands.joinToString("+")}catch(_:Exception){""};tac=id.tac.toString();ci=id.nci.toString();rsrp=s.ssRsrp.toString();rsrq=s.ssRsrq.toString();sinr=s.ssSinr.toString()}
            }
            if(sample%5==0){
                val td=System.nanoTime();try{val a=InetAddress.getAllByName("google.com");dns="OK:${(System.nanoTime()-td)/1_000_000}ms:${a.firstOrNull()?.hostAddress}"}catch(e:Exception){dns="FAIL:${e.javaClass.simpleName}"}
                val th=System.nanoTime();try{val h=URL("https://www.google.com/generate_204").openConnection() as HttpURLConnection;h.connectTimeout=4000;h.readTimeout=4000;h.useCaches=false;h.connect();https="${h.responseCode}:${(System.nanoTime()-th)/1_000_000}ms";h.disconnect()}catch(e:Exception){https="FAIL:${e.javaClass.simpleName}"}
            }
        }catch(e:Exception){https="ERR:${e.javaClass.simpleName}"}
        appendLine("SAMPLE,${now()},$sample,$transport,$rat,$voice,$data,$dataState,${netEvent.replace(',',';')},$registered,$pci,$earfcn,$band,$tac,$ci,$rsrp,$rsrq,$rssi,$sinr,$dns,$https")
        val text=synchronized(this){log.toString()};runOnUiThread{output.text=text.takeLast(30000)}
    }

    private fun stopMonitor(){monitor?.cancel(false);monitor=null;try{(getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(callback)}catch(_:Exception){};runButton.isEnabled=true;stopButton.isEnabled=false;output.text=synchronized(this){log.toString().takeLast(60000)}}
    override fun onDestroy(){monitor?.cancel(true);try{(getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(callback)}catch(_:Exception){};executor.shutdownNow();super.onDestroy()}
}
