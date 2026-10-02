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
    private lateinit var telemetry: TelemetryUploader
    private val executor=Executors.newSingleThreadScheduledExecutor()
    private val uploadExecutor=Executors.newSingleThreadExecutor()
    private var monitor:ScheduledFuture<*>?=null
    private val lines=ArrayDeque<String>()
    private var sample=0
    @Volatile private var netEvent="INIT"
    private var failureStartedAt:Long?=null
    private var recoveryAttempts=0
    private var nextRecoveryAt=0L
    private var recoveryCallback:ConnectivityManager.NetworkCallback?=null
    private var lastGoodCell=""
    private var lastDnsOk=true
    private var lastHttpsOk=true

    private fun now()=SimpleDateFormat("HH:mm:ss.SSS",Locale.US).format(Date())
    @Synchronized private fun appendLine(s:String){lines.addLast(s);while(lines.size>900)lines.removeFirst()}
    @Synchronized private fun currentLog()=lines.joinToString("\n")
    private fun callbackEvent(s:String){netEvent=s;appendLine("EVENT,${now()},${s.replace(',',';')}")}
    private fun uploadSnapshot(summary:String){
        telemetry.enqueue(summary,currentLog())
        uploadExecutor.execute{val r=telemetry.flush();appendLine("TELEMETRY,${now()},$r")}
    }

    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState);telemetry=TelemetryUploader(applicationContext)
        val d=resources.displayMetrics.density;val pad=(16*d).toInt()
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(pad,pad,pad,pad)}
        runButton=Button(this).apply{text="開始網路自動補救 v9";textSize=18f;setTextColor(Color.WHITE);setBackgroundColor(Color.rgb(0,100,200));isAllCaps=false;layoutParams=LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,(72*d).toInt()).apply{bottomMargin=pad}}
        stopButton=Button(this).apply{text="停止監測";isAllCaps=false;isEnabled=false}
        output=TextView(this).apply{text="Network Diagnostic v9\n持續監測 LTE/DNS/HTTPS；失敗時每 10 秒重新要求 CELLULAR+INTERNET，恢復後驗證 Neon DNS/HTTPS。每 2 分鐘及故障/恢復事件上傳精簡監測快照，供遠端讀取。Log 僅保留最近 900 行。";textSize=14f;setTextIsSelectable(true)}
        box.addView(runButton);box.addView(stopButton);box.addView(output);setContentView(ScrollView(this).apply{addView(box)})
        runButton.setOnClickListener{permissionsAndRun()};stopButton.setOnClickListener{stopMonitor()}
    }
    private fun permissionsAndRun(){val p=arrayOf(Manifest.permission.READ_PHONE_STATE,Manifest.permission.ACCESS_FINE_LOCATION);val m=p.filter{ActivityCompat.checkSelfPermission(this,it)!=PackageManager.PERMISSION_GRANTED};if(m.isNotEmpty())ActivityCompat.requestPermissions(this,m.toTypedArray(),9)else startMonitor()}
    override fun onRequestPermissionsResult(r:Int,p:Array<out String>,g:IntArray){super.onRequestPermissionsResult(r,p,g);if(r==9&&g.all{it==PackageManager.PERMISSION_GRANTED})startMonitor()}

    private val defaultCallback=object:ConnectivityManager.NetworkCallback(){
        override fun onAvailable(n:Network)=callbackEvent("AVAILABLE:$n")
        override fun onLost(n:Network)=callbackEvent("LOST:$n")
        override fun onLosing(n:Network,maxMs:Int)=callbackEvent("LOSING:$n:$maxMs")
        override fun onUnavailable()=callbackEvent("UNAVAILABLE")
        override fun onCapabilitiesChanged(n:Network,c:NetworkCapabilities)=callbackEvent("CAP:$n:${if(c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))"CELL" else if(c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))"WIFI" else "OTHER"}:VALID=${c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}")
        override fun onLinkPropertiesChanged(n:Network,lp:LinkProperties)=callbackEvent("LINK:$n:DNS=${lp.dnsServers.joinToString("+")}:IF=${lp.interfaceName}")
    }

    private fun startMonitor(){
        monitor?.cancel(false);synchronized(this){lines.clear()};sample=0;netEvent="START";failureStartedAt=null;recoveryAttempts=0;nextRecoveryAt=0;lastGoodCell="";lastDnsOk=true;lastHttpsOk=true
        val cm=getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        try{cm.unregisterNetworkCallback(defaultCallback)}catch(_:Exception){};cm.registerDefaultNetworkCallback(defaultCallback)
        appendLine("=== Network Diagnostic v9 persistent recovery + telemetry ===")
        appendLine("SAMPLE,time,n,transport,rat,voiceReg,dataState,lastEvent,registered,pci,earfcn,band,tac,ci,rsrp,rsrq,rssi,sinr,dns,https,recovery")
        runButton.isEnabled=false;stopButton.isEnabled=true;monitor=executor.scheduleAtFixedRate({takeSample()},0,1,TimeUnit.SECONDS)
        uploadSnapshot("MONITOR_STARTED")
    }

    private fun requestCellularRecovery(reason:String):String{
        val cm=getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager;recoveryAttempts++;val attempt=recoveryAttempts;nextRecoveryAt=System.currentTimeMillis()+10000
        appendLine("RECOVERY,${now()},REQUEST_CELLULAR,attempt=$attempt,reason=$reason");uploadSnapshot("REQUEST_CELLULAR#$attempt:$reason")
        recoveryCallback?.let{try{cm.unregisterNetworkCallback(it)}catch(_:Exception){}}
        val cb=object:ConnectivityManager.NetworkCallback(){
            override fun onAvailable(network:Network){val bound=cm.bindProcessToNetwork(network);appendLine("RECOVERY,${now()},CELLULAR_AVAILABLE,attempt=$attempt,network=$network,bound=$bound");executor.execute{probeOnNetwork(network,attempt)}}
            override fun onCapabilitiesChanged(network:Network,caps:NetworkCapabilities){if(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))appendLine("RECOVERY,${now()},VALIDATED,attempt=$attempt,network=$network")}
            override fun onLost(network:Network){appendLine("RECOVERY,${now()},RECOVERY_NETWORK_LOST,attempt=$attempt,network=$network")}
            override fun onUnavailable(){appendLine("RECOVERY,${now()},UNAVAILABLE,attempt=$attempt")}
        };recoveryCallback=cb
        val req=NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        try{cm.requestNetwork(req,cb,10000)}catch(e:Exception){appendLine("RECOVERY,${now()},REQUEST_FAILED,attempt=$attempt,${e.javaClass.simpleName}:${e.message}");uploadSnapshot("REQUEST_FAILED#$attempt:${e.javaClass.simpleName}")}
        return "REQUEST_CELLULAR#$attempt"
    }

    private fun probeOnNetwork(network:Network,attempt:Int){try{val a=network.getAllByName("ep-autumn-shadow-aesref0t.apirest.c-2.us-east-2.aws.neon.tech");appendLine("RECOVERY,${now()},NEON_DNS_OK,attempt=$attempt,ip=${a.firstOrNull()?.hostAddress}");val c=network.openConnection(URL("https://www.google.com/generate_204")) as HttpURLConnection;c.connectTimeout=4000;c.readTimeout=4000;c.useCaches=false;c.connect();appendLine("RECOVERY,${now()},HTTPS_OK,attempt=$attempt,code=${c.responseCode}");c.disconnect()}catch(e:Exception){appendLine("RECOVERY,${now()},PROBE_FAIL,attempt=$attempt,${e.javaClass.simpleName}:${e.message}")}}

    private fun recoveryState(registered:Boolean,transport:String,dnsOk:Boolean,httpsOk:Boolean,cell:String):String{
        if(cell.isNotBlank())lastGoodCell=cell;val badRadio=!registered||transport=="NONE";val badData=!dnsOk||!httpsOk;val bad=badRadio||badData;val t=System.currentTimeMillis()
        if(!bad){val start=failureStartedAt;if(start!=null){val sec=(t-start)/1000;appendLine("RECOVERY,${now()},RECOVERED,${sec}s,attempts=$recoveryAttempts,cell=$cell");uploadSnapshot("RECOVERED:${sec}s:attempts=$recoveryAttempts");failureStartedAt=null;recoveryAttempts=0;nextRecoveryAt=0;return "RECOVERED:${sec}s"};return "OK"}
        if(failureStartedAt==null){failureStartedAt=t;nextRecoveryAt=t+5000;appendLine("RECOVERY,${now()},FAILURE_START,radio=$badRadio,data=$badData,lastCell=$lastGoodCell");uploadSnapshot("FAILURE_START:radio=$badRadio:data=$badData");return "FAILURE_START"}
        val sec=(t-failureStartedAt!!)/1000
        if(t>=nextRecoveryAt)return requestCellularRecovery(if(badRadio)"RADIO_OR_REGISTRATION" else "DNS_OR_HTTPS")
        return "WAIT:${sec}s"
    }

    private fun takeSample(){
        sample++;var transport="NONE";var rat="?";var voice="?";var dataState="?";var registered=false;var pci="";var earfcn="";var band="";var tac="";var ci="";var rsrp="";var rsrq="";var rssi="";var sinr="";var dns="-";var https="-";var dnsOk=lastDnsOk;var httpsOk=lastHttpsOk
        try{val cm=getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager;val caps=cm.getNetworkCapabilities(cm.activeNetwork);transport=when{caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)==true->"CELLULAR";caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true->"WIFI";else->"NONE"};val tm=getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager;rat=when(tm.dataNetworkType){TelephonyManager.NETWORK_TYPE_LTE->"LTE";TelephonyManager.NETWORK_TYPE_NR->"NR";else->tm.dataNetworkType.toString()};val ss=tm.serviceState;voice=when(ss?.state){ServiceState.STATE_IN_SERVICE->"IN";ServiceState.STATE_OUT_OF_SERVICE->"OUT";ServiceState.STATE_EMERGENCY_ONLY->"EMERGENCY";ServiceState.STATE_POWER_OFF->"OFF";else->"?"};dataState=when(tm.dataState){TelephonyManager.DATA_CONNECTED->"CONNECTED";TelephonyManager.DATA_CONNECTING->"CONNECTING";TelephonyManager.DATA_DISCONNECTED->"DISCONNECTED";TelephonyManager.DATA_SUSPENDED->"SUSPENDED";else->tm.dataState.toString()};val serving=tm.allCellInfo?.firstOrNull{it.isRegistered};when(serving){is CellInfoLte->{val s=serving.cellSignalStrength;val id=serving.cellIdentity;registered=true;pci=id.pci.toString();earfcn=id.earfcn.toString();band=try{id.bands.joinToString("+")}catch(_:Exception){""};tac=id.tac.toString();ci=id.ci.toString();rsrp=s.rsrp.toString();rsrq=s.rsrq.toString();rssi=s.rssi.toString();sinr=s.rssnr.toString()};is CellInfoNr->{val s=serving.cellSignalStrength as CellSignalStrengthNr;val id=serving.cellIdentity as CellIdentityNr;registered=true;pci=id.pci.toString();earfcn=id.nrarfcn.toString();band=try{id.bands.joinToString("+")}catch(_:Exception){""};tac=id.tac.toString();ci=id.nci.toString();rsrp=s.ssRsrp.toString();rsrq=s.ssRsrq.toString();sinr=s.ssSinr.toString()}}
            if(sample%5==0){val td=System.nanoTime();try{val a=InetAddress.getAllByName("ep-autumn-shadow-aesref0t.apirest.c-2.us-east-2.aws.neon.tech");dnsOk=true;dns="OK:${(System.nanoTime()-td)/1_000_000}ms:${a.firstOrNull()?.hostAddress}"}catch(e:Exception){dnsOk=false;dns="FAIL:${e.javaClass.simpleName}"};lastDnsOk=dnsOk;val th=System.nanoTime();try{val h=URL("https://www.google.com/generate_204").openConnection() as HttpURLConnection;h.connectTimeout=4000;h.readTimeout=4000;h.useCaches=false;h.connect();httpsOk=h.responseCode in 200..399;https="${h.responseCode}:${(System.nanoTime()-th)/1_000_000}ms";h.disconnect()}catch(e:Exception){httpsOk=false;https="FAIL:${e.javaClass.simpleName}"};lastHttpsOk=httpsOk}}
        catch(e:Exception){httpsOk=false;lastHttpsOk=false;https="ERR:${e.javaClass.simpleName}"}
        val cell="$pci/$earfcn/$ci";val recovery=recoveryState(registered,transport,dnsOk,httpsOk,cell);appendLine("SAMPLE,${now()},$sample,$transport,$rat,$voice,$dataState,${netEvent.replace(',',';')},$registered,$pci,$earfcn,$band,$tac,$ci,$rsrp,$rsrq,$rssi,$sinr,$dns,$https,$recovery")
        if(sample%120==0)uploadSnapshot("HEARTBEAT:$transport:$rat:reg=$registered:dns=$dnsOk:https=$httpsOk:recovery=$recovery")
        val text=currentLog();runOnUiThread{output.text=text.takeLast(50000)}
    }

    private fun stopMonitor(){monitor?.cancel(false);monitor=null;val cm=getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager;try{cm.unregisterNetworkCallback(defaultCallback)}catch(_:Exception){};recoveryCallback?.let{try{cm.unregisterNetworkCallback(it)}catch(_:Exception){}};cm.bindProcessToNetwork(null);uploadSnapshot("MONITOR_STOPPED");runButton.isEnabled=true;stopButton.isEnabled=false;output.text=currentLog().takeLast(60000)}
    override fun onDestroy(){stopMonitor();executor.shutdownNow();uploadExecutor.shutdown();super.onDestroy()}
}
