package com.andrs002.networkdiagnostic

import android.Manifest
import android.content.Context
import android.content.ComponentName
import android.content.res.ColorStateList
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.*
import android.os.Bundle
import android.telephony.*
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {
    private lateinit var output:TextView; private lateinit var statusLine:TextView; private lateinit var runButton:Button; private lateinit var stopButton:Button; private lateinit var grantButton:Button; private lateinit var resetNowButton:Button; private lateinit var telemetry:TelemetryUploader; private lateinit var shizukuRecovery:ShizukuRecovery
    private val executor=Executors.newSingleThreadScheduledExecutor(); private val uploadExecutor=Executors.newSingleThreadExecutor(); private var monitor:ScheduledFuture<*>?=null
    private val uiHandler = Handler(Looper.getMainLooper())
    private val cycling=AtomicBoolean(false);@Volatile private var lastDataCycleAt=0L
    private val lines=ArrayDeque<String>(); private var sample=0; @Volatile private var netEvent="INIT"; private var failureStartedAt:Long?=null; private var recoveryAttempts=0; private var nextRecoveryAt=0L; private var recoveryCallback:ConnectivityManager.NetworkCallback?=null
    private var lastGoodCell=""; private var lastDnsOk=true; private var lastHttpsOk=true; @Volatile private var lastRegisteredAt=0L
    private fun now()=SimpleDateFormat("HH:mm:ss.SSS",Locale.US).format(Date())
    @Synchronized private fun appendLine(s:String){lines.addLast(s);while(lines.size>1200)lines.removeFirst()}
    @Synchronized private fun currentLog()=lines.joinToString("\n")
    private fun callbackEvent(s:String){netEvent=s;appendLine("EVENT,${now()},${s.replace(',',';')}")}
    private fun uploadSnapshot(summary:String){telemetry.enqueue(summary,currentLog());uploadExecutor.execute{appendLine("TELEMETRY,${now()},${telemetry.flush()}")}}

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        // Android 15/16 enforce edge-to-edge; keep actionable controls clear of
        // status and gesture/navigation bars on Samsung and other devices.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        supportActionBar?.hide()
        telemetry = TelemetryUploader(applicationContext)
        val density = resources.displayMetrics.density
        val dp = { value: Int -> (value * density + 0.5f).toInt() }

        // Only the diagnostic output scrolls. Recovery controls stay above the
        // navigation bar, reachable even when the log grows to thousands of lines.
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, windowInsets ->
            val safe = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(
                dp(12) + safe.left,
                dp(8) + safe.top,
                dp(12) + safe.right,
                dp(8) + safe.bottom
            )
            windowInsets
        }

        val heading = TextView(this).apply {
            text = "網路診斷 v12　｜　監測紀錄"
            setTextColor(Color.rgb(35, 45, 58))
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(4), dp(4), dp(4), dp(8))
        }
        root.addView(heading)

        output = TextView(this).apply {
            text = "監測尚未啟動。\n可啟用本 App 輔助重連，不需要 Shizuku、Wi-Fi 或電腦。"
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.rgb(30, 40, 50))
            setTextIsSelectable(true)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val logScroll = ScrollView(this).apply {
            isFillViewport = true
            addView(output)
        }
        root.addView(
            logScroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        )

        // Fixed control panel, separate from the scrolling log.
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(243, 246, 251))
            setPadding(dp(8), dp(10), dp(8), dp(10))
            elevation = dp(4).toFloat()
        }

        statusLine = TextView(this).apply {
            text = "輔助重連：尚未啟用"
            textSize = 13f
            setTextColor(Color.rgb(55, 65, 80))
            maxLines = 2
            setPadding(dp(4), 0, dp(4), dp(7))
        }
        controls.addView(statusLine)

        grantButton = Button(this).apply {
            text = "啟用輔助重連（不需 Shizuku）"
            textSize = 15f
            isAllCaps = false
            setTextColor(Color.rgb(30, 45, 65))
            backgroundTintList = ColorStateList.valueOf(Color.rgb(220, 229, 241))
        }
        controls.addView(
            grantButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(54)
            ).apply { bottomMargin = dp(8) }
        )

        runButton = Button(this).apply {
            text = "開始監測"
            textSize = 16f
            isAllCaps = false
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(Color.rgb(26, 99, 180))
        }
        stopButton = Button(this).apply {
            text = "停止監測"
            textSize = 16f
            isAllCaps = false
            isEnabled = false
        }
        val monitorRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(
                runButton,
                LinearLayout.LayoutParams(0, dp(58), 1f).apply {
                    rightMargin = dp(6)
                }
            )
            addView(
                stopButton,
                LinearLayout.LayoutParams(0, dp(58), 1f).apply {
                    leftMargin = dp(6)
                }
            )
        }
        controls.addView(
            monitorRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(58)
            ).apply { bottomMargin = dp(10) }
        )

        resetNowButton = Button(this).apply {
            text = "立即重連行動數據"
            textSize = 19f
            setTypeface(null, Typeface.BOLD)
            isAllCaps = false
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(Color.rgb(0, 115, 113))
        }
        controls.addView(
            resetNowButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(76)
            )
        )

        root.addView(
            controls,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        setContentView(root)

        shizukuRecovery = ShizukuRecovery(this) { message ->
            appendLine("SHIZUKU," + now() + "," + message)
            uploadSnapshot("SHIZUKU:" + message)
            runOnUiThread {
                statusLine.text = when {
                    message == "SHIZUKU_SERVICE_READY" -> "進階 Shizuku：已授權"
                    message == "SHIZUKU_BINDING_SERVICE" -> "進階 Shizuku：正在連線…"
                    message == "SHIZUKU_REQUESTING_PERMISSION" -> "進階 Shizuku：等待授權"
                    message.startsWith("SHIZUKU_NOT_RUNNING") -> "輔助重連不需 Shizuku"
                    message == "SHIZUKU_PERMISSION_DENIED" -> "Shizuku：權限未允許"
                    message == "SHIZUKU_BINDER_DEAD" -> "Shizuku：連線中斷"
                    else -> "Shizuku：" + message
                }
            }
        }
        grantButton.setOnClickListener {
            val enabled = isQuickRecoveryEnabledByAndroid()
            if (enabled) {
                updateQuickRecoveryStatus()
                if (QuickPanelRecoveryService.active == null) {
                    Toast.makeText(this, "系統已開啟輔助服務，但程式尚未連線，請稍候再檢查", Toast.LENGTH_LONG).show()
                    appendLine("ACCESSIBILITY," + now() + ",ENABLED_BUT_NOT_CONNECTED")
                    uploadSnapshot("ACCESSIBILITY_ENABLED_NOT_CONNECTED")
                    scheduleRecoveryStatusChecks()
                } else {
                    Toast.makeText(this, "輔助重連服務已連線", Toast.LENGTH_SHORT).show()
                }
            } else {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                Toast.makeText(this, "請啟用「網路診斷：快速重連輔助」", Toast.LENGTH_LONG).show()
            }
        }
        runButton.setOnClickListener { permissionsAndRun() }
        stopButton.setOnClickListener { stopMonitor() }
        resetNowButton.setOnClickListener { beginManualRecovery() }
    }

    /** Reads actual Android accessibility settings, not only the service singleton. */
    private fun isQuickRecoveryEnabledByAndroid(): Boolean {
        val target = ComponentName(this, QuickPanelRecoveryService::class.java)
        return try {
            val setting = Settings.Secure.getString(
                contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            setting.split(':').any { entry ->
                val component = ComponentName.unflattenFromString(entry.trim())
                component?.packageName == target.packageName &&
                    component.className == target.className
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun updateQuickRecoveryStatus() {
        if (!::statusLine.isInitialized) return
        val enabledInSystem = isQuickRecoveryEnabledByAndroid()
        val connected = QuickPanelRecoveryService.active != null
        statusLine.text = when {
            connected -> "輔助重連：已連線，可以手動測試"
            enabledInSystem -> "輔助重連：系統已開啟，等待服務連線"
            else -> "輔助重連：系統尚未啟用"
        }
        grantButton.text = when {
            connected -> "輔助重連已連線"
            enabledInSystem -> "重新檢查輔助服務連線"
            else -> "啟用輔助重連（不需 Shizuku）"
        }
        appendLine(
            "ACCESSIBILITY," + now() + ",ENABLED_IN_SYSTEM=" +
                enabledInSystem + ",SERVICE_CONNECTED=" + connected
        )
    }

    private fun scheduleRecoveryStatusChecks() {
        uiHandler.postDelayed({ if (!isFinishing && !isDestroyed) updateQuickRecoveryStatus() }, 700L)
        uiHandler.postDelayed({ if (!isFinishing && !isDestroyed) updateQuickRecoveryStatus() }, 2200L)
    }

    override fun onResume() {
        super.onResume()
        updateQuickRecoveryStatus()
        scheduleRecoveryStatusChecks()
    }

    private fun beginManualRecovery() {
        if (ActivityCompat.checkSelfPermission(
                this, Manifest.permission.READ_PHONE_STATE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.READ_PHONE_STATE), 10
            )
            return
        }
        val service = QuickPanelRecoveryService.active
        if (service != null) {
            statusLine.text = "輔助重連：正在操作系統快速設定…"
            appendLine("RECOVERY," + now() + ",UI_RECOVERY_REQUESTED_BY_USER")
            uploadSnapshot("UI_RECOVERY_REQUESTED")
            service.requestManualCycle { result ->
                appendLine("RECOVERY," + now() + "," + result)
                uploadSnapshot("UI_RECOVERY_RESULT:" + result)
                runOnUiThread {
                    statusLine.text = when {
                        result == "UI_DATA_ENABLED_VERIFY_INTERNET_SEPARATELY" ->
                            "已重新開啟數據，仍需驗證能否上網"
                        result.contains("MANUALLY") ->
                            "未能完成：請在快速設定確認行動數據已開啟"
                        else -> "輔助重連：" + result
                    }
                    output.text = currentLog().takeLast(60000)
                    Toast.makeText(this, statusLine.text, Toast.LENGTH_LONG).show()
                }
            }
        } else if (shizukuRecovery.enabled) {
            executor.execute {
                val result = performDataCycle("MANUAL")
                appendLine("MANUAL," + now() + "," + result)
                runOnUiThread {
                    statusLine.text = "Shizuku：" + result
                    output.text = currentLog().takeLast(60000)
                }
            }
        } else if (isQuickRecoveryEnabledByAndroid()) {
            statusLine.text = "系統已授權，服務尚未連線，請稍後再按重連"
            appendLine("ACCESSIBILITY," + now() + ",ENABLED_BUT_NO_SERVICE")
            uploadSnapshot("ACCESSIBILITY_ENABLED_NO_SERVICE")
            Toast.makeText(this, "無障礙服務已開啟，但尚未連線；不必重新授權", Toast.LENGTH_LONG).show()
            scheduleRecoveryStatusChecks()
        } else {
            Toast.makeText(
                this, "請先啟用「網路診斷：快速重連輔助」服務",
                Toast.LENGTH_LONG
            ).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private fun permissionsAndRun(){val p=arrayOf(Manifest.permission.READ_PHONE_STATE,Manifest.permission.ACCESS_FINE_LOCATION);val m=p.filter{ActivityCompat.checkSelfPermission(this,it)!=PackageManager.PERMISSION_GRANTED};if(m.isNotEmpty())ActivityCompat.requestPermissions(this,m.toTypedArray(),9)else startMonitor()}
    override fun onRequestPermissionsResult(r:Int,p:Array<out String>,g:IntArray){super.onRequestPermissionsResult(r,p,g);if(r==9&&g.all{it==PackageManager.PERMISSION_GRANTED})startMonitor();if(r==10&&g.isNotEmpty()&&g.all{it==PackageManager.PERMISSION_GRANTED})beginManualRecovery()}

    private val defaultCallback=object:ConnectivityManager.NetworkCallback(){
        override fun onAvailable(n:Network)=callbackEvent("AVAILABLE:$n"); override fun onLost(n:Network)=callbackEvent("LOST:$n")
        override fun onCapabilitiesChanged(n:Network,c:NetworkCapabilities)=callbackEvent("CAP:$n:CELL=${c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)}:VALID=${c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}:INTERNET=${c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)}:SUSP=${!c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)}")
        override fun onLinkPropertiesChanged(n:Network,lp:LinkProperties)=callbackEvent("LINK:$n:${linkSummary(lp)}")}
    private fun linkSummary(lp:LinkProperties?):String{if(lp==null)return "LP=null";return "IF=${lp.interfaceName}:ADDR=${lp.linkAddresses.joinToString("+")}:DNS=${lp.dnsServers.joinToString("+")}:ROUTES=${lp.routes.joinToString("+"){it.toString()}}"}
    private fun networkSummary(cm:ConnectivityManager,n:Network?):String{if(n==null)return "NET=null";val c=cm.getNetworkCapabilities(n);val lp=cm.getLinkProperties(n);return "NET=$n:CELL=${c?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)}:VALID=${c?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}:INTERNET=${c?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)}:SUSP=${c?.let{!it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)}}:${linkSummary(lp)}"}

    private fun startMonitor(){
        val serviceIntent=Intent(this,NetworkMonitorService::class.java)
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.O) startForegroundService(serviceIntent) else startService(serviceIntent)
        monitor?.cancel(false);synchronized(this){lines.clear()};sample=0;failureStartedAt=null;recoveryAttempts=0;nextRecoveryAt=0;lastGoodCell="";lastDnsOk=true;lastHttpsOk=true;lastRegisteredAt=System.currentTimeMillis();val cm=getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager;try{cm.unregisterNetworkCallback(defaultCallback)}catch(_:Exception){};cm.bindProcessToNetwork(null);cm.registerDefaultNetworkCallback(defaultCallback);appendLine("=== Network Diagnostic v12: manual Android Quick Settings recovery ===");appendLine("SAMPLE,time,n,transport,rat,voiceReg,dataState,event,registered,pci,earfcn,band,tac,ci,rsrp,rsrq,rssi,sinr,dns,https,recovery");runButton.isEnabled=false;stopButton.isEnabled=true;monitor=executor.scheduleAtFixedRate({takeSample()},0,1,TimeUnit.SECONDS);uploadSnapshot("MONITOR_STARTED_V12")}

    private fun requestCellularRecovery(reason:String):String{val cm=getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager;recoveryAttempts++;val a=recoveryAttempts;nextRecoveryAt=System.currentTimeMillis()+10000;cm.bindProcessToNetwork(null);recoveryCallback?.let{try{cm.unregisterNetworkCallback(it)}catch(_:Exception){}};recoveryCallback=null
        appendLine("RECOVERY,${now()},RESET_OLD_REQUEST,attempt=$a");appendLine("RECOVERY,${now()},REQUEST_CELLULAR,attempt=$a,reason=$reason");uploadSnapshot("REQUEST_CELLULAR#$a:$reason")
        val cb=object:ConnectivityManager.NetworkCallback(){override fun onAvailable(n:Network){appendLine("RECOVERY,${now()},CELLULAR_AVAILABLE,attempt=$a,${networkSummary(cm,n)}");executor.execute{deepProbe(n,a)}};override fun onCapabilitiesChanged(n:Network,c:NetworkCapabilities){appendLine("RECOVERY,${now()},RECOVERY_CAP,attempt=$a,${networkSummary(cm,n)}")};override fun onLinkPropertiesChanged(n:Network,lp:LinkProperties){appendLine("RECOVERY,${now()},RECOVERY_LINK,attempt=$a,${linkSummary(lp)}")};override fun onLost(n:Network){appendLine("RECOVERY,${now()},RECOVERY_NETWORK_LOST,attempt=$a,network=$n")};override fun onUnavailable(){appendLine("RECOVERY,${now()},UNAVAILABLE,attempt=$a")}}
        recoveryCallback=cb;val req=NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build();try{cm.requestNetwork(req,cb,10000)}catch(e:Exception){appendLine("RECOVERY,${now()},REQUEST_FAILED,attempt=$a,${e.javaClass.simpleName}:${e.message}")};return "REQUEST_CELLULAR#$a"}

    private fun performDataCycle(source:String):String{if(!cycling.compareAndSet(false,true))return "SKIPPED_ALREADY_CYCLING";try{val t=System.currentTimeMillis();if(t-lastDataCycleAt<180000)return "SKIPPED_COOLDOWN";val tm=getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager;val busy=try{tm.callState!=TelephonyManager.CALL_STATE_IDLE}catch(_:Exception){true};if(busy)return "SKIPPED_ACTIVE_CALL";if(!shizukuRecovery.enabled)return "SKIPPED_SHIZUKU_NOT_READY";lastDataCycleAt=t;appendLine("RECOVERY,"+now()+",DATA_CYCLE_START,source="+source);uploadSnapshot("DATA_CYCLE_START:"+source);val result=shizukuRecovery.cycle();appendLine("RECOVERY,"+now()+","+result);uploadSnapshot("DATA_CYCLE_RESULT:"+result);return result}finally{cycling.set(false)}}

    private fun deepProbe(n:Network,a:Int){val cm=getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager;appendLine("DEEP,${now()},attempt=$a,${networkSummary(cm,n)}")
        try{val ips=n.getAllByName("www.google.com");appendLine("DEEP,${now()},DNS_OK,attempt=$a,${ips.joinToString("+"){it.hostAddress?:"?"}}") }catch(e:Exception){appendLine("DEEP,${now()},DNS_FAIL,attempt=$a,${e.javaClass.simpleName}:${e.message}")}
        try{val s=n.socketFactory.createSocket() as Socket;s.connect(InetSocketAddress("1.1.1.1",443),3500);appendLine("DEEP,${now()},IPV4_SOCKET_OK,attempt=$a,remote=1.1.1.1:443");s.close()}catch(e:Exception){appendLine("DEEP,${now()},IPV4_SOCKET_FAIL,attempt=$a,${e.javaClass.simpleName}:${e.message}")}
        try{val h=n.openConnection(URL("https://www.google.com/generate_204")) as HttpURLConnection;h.connectTimeout=4000;h.readTimeout=4000;h.useCaches=false;val code=h.responseCode;appendLine("DEEP,${now()},BOUND_HTTPS,attempt=$a,code=$code");h.disconnect()}catch(e:Exception){appendLine("DEEP,${now()},BOUND_HTTPS_FAIL,attempt=$a,${e.javaClass.simpleName}:${e.message}")}
        uploadSnapshot("DEEP_PROBE#$a")}

    private fun recoveryState(reg:Boolean,tr:String,dns:Boolean,https:Boolean,cell:String):String{val t=System.currentTimeMillis();if(reg){lastRegisteredAt=t;if(cell.isNotBlank())lastGoodCell=cell};if(tr=="WIFI"){if(failureStartedAt!=null){appendLine("RECOVERY,${now()},CANCELLED_WIFI_ACTIVE,attempts=$recoveryAttempts");uploadSnapshot("RECOVERY_CANCELLED_WIFI_ACTIVE")};failureStartedAt=null;recoveryAttempts=0;nextRecoveryAt=0;recoveryCallback?.let{try{(getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(it)}catch(_:Exception){}};recoveryCallback=null;return "WIFI_ACTIVE"};val cellInfoStale=!reg && (t-lastRegisteredAt)>5000;val br=tr=="NONE";val bd=!dns||!https;val bad=bd||br;if(!bad){failureStartedAt?.let{val sec=(t-it)/1000;appendLine("RECOVERY,${now()},RECOVERED,${sec}s,attempts=$recoveryAttempts,cell=$cell");uploadSnapshot("RECOVERED:${sec}s:attempts=$recoveryAttempts");failureStartedAt=null;recoveryAttempts=0;nextRecoveryAt=0;(getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).bindProcessToNetwork(null);return "RECOVERED:${sec}s"};return "OK"};if(failureStartedAt==null){failureStartedAt=t;nextRecoveryAt=t+12000;appendLine("RECOVERY,${now()},FAILURE_START,radio=$br,data=$bd,lastCell=$lastGoodCell");uploadSnapshot("FAILURE_START:radio=$br:data=$bd");return "FAILURE_START"};val sec=(t-failureStartedAt!!)/1000;if(t>=nextRecoveryAt){nextRecoveryAt=t+180000;val tm=getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager;val onCall=try{tm.callState!=TelephonyManager.CALL_STATE_IDLE}catch(_:Exception){true};if(onCall){appendLine("RECOVERY,"+now()+",SKIP_ACTIVE_CALL");return "SKIP_ACTIVE_CALL"};if(!shizukuRecovery.enabled){appendLine("RECOVERY,"+now()+",SHIZUKU_NOT_AUTHORIZED_NO_REPAIR");uploadSnapshot("RECOVERY_UNAVAILABLE:SHIZUKU_NOT_AUTHORIZED");return "NO_PRIVILEGED_RECOVERY"};val result=performDataCycle("AUTO");return if(result.startsWith("DATA_CYCLE_CMD:"))"DATA_CYCLE_ATTEMPTED" else result};return "WAIT:${sec}s"}

    private fun takeSample(){sample++;var tr="NONE";var rat="?";var voice="?";var ds="?";var reg=false;var pci="";var ef="";var band="";var tac="";var ci="";var rsrp="";var rsrq="";var rssi="";var sinr="";var dns="-";var https="-";var dok=lastDnsOk;var hok=lastHttpsOk
        try{val cm=getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager;val active=cm.activeNetwork;val caps=cm.getNetworkCapabilities(active);tr=when{caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)==true->"CELLULAR";caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true->"WIFI";else->"NONE"};if(sample%5==0)appendLine("STATE,${now()},${networkSummary(cm,active)}");val tm=getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager;rat=when(tm.dataNetworkType){TelephonyManager.NETWORK_TYPE_LTE->"LTE";TelephonyManager.NETWORK_TYPE_NR->"NR";else->tm.dataNetworkType.toString()};voice=when(tm.serviceState?.state){ServiceState.STATE_IN_SERVICE->"IN";ServiceState.STATE_OUT_OF_SERVICE->"OUT";ServiceState.STATE_EMERGENCY_ONLY->"EMERGENCY";ServiceState.STATE_POWER_OFF->"OFF";else->"?"};ds=when(tm.dataState){TelephonyManager.DATA_CONNECTED->"CONNECTED";TelephonyManager.DATA_CONNECTING->"CONNECTING";TelephonyManager.DATA_DISCONNECTED->"DISCONNECTED";TelephonyManager.DATA_SUSPENDED->"SUSPENDED";else->tm.dataState.toString()};when(val s=tm.allCellInfo?.firstOrNull{it.isRegistered}){is CellInfoLte->{val x=s.cellSignalStrength;val id=s.cellIdentity;reg=true;pci=id.pci.toString();ef=id.earfcn.toString();band=try{id.bands.joinToString("+")}catch(_:Exception){""};tac=id.tac.toString();ci=id.ci.toString();rsrp=x.rsrp.toString();rsrq=x.rsrq.toString();rssi=x.rssi.toString();sinr=x.rssnr.toString()};is CellInfoNr->{val x=s.cellSignalStrength as CellSignalStrengthNr;val id=s.cellIdentity as CellIdentityNr;reg=true;pci=id.pci.toString();ef=id.nrarfcn.toString();band=try{id.bands.joinToString("+")}catch(_:Exception){""};tac=id.tac.toString();ci=id.nci.toString();rsrp=x.ssRsrp.toString();rsrq=x.ssRsrq.toString();sinr=x.ssSinr.toString()}}
            if(sample%5==0){val td=System.nanoTime();try{val a=InetAddress.getAllByName("www.google.com");dok=true;dns="OK:${(System.nanoTime()-td)/1_000_000}ms:${a.firstOrNull()?.hostAddress}"}catch(e:Exception){dok=false;dns="FAIL:${e.javaClass.simpleName}"};lastDnsOk=dok;val th=System.nanoTime();try{val h=URL("https://www.google.com/generate_204").openConnection() as HttpURLConnection;h.connectTimeout=4000;h.readTimeout=4000;h.useCaches=false;val code=h.responseCode;hok=code in 200..399;https="$code:${(System.nanoTime()-th)/1_000_000}ms";h.disconnect()}catch(e:Exception){hok=false;https="FAIL:${e.javaClass.simpleName}"};lastHttpsOk=hok}}
        catch(e:Exception){hok=false;lastHttpsOk=false;https="ERR:${e.javaClass.simpleName}"};val cell="$pci/$ef/$ci";val rec=recoveryState(reg,tr,dok,hok,cell);appendLine("SAMPLE,${now()},$sample,$tr,$rat,$voice,$ds,${netEvent.replace(',',';')},$reg,$pci,$ef,$band,$tac,$ci,$rsrp,$rsrq,$rssi,$sinr,$dns,$https,$rec");if(sample%10==0)uploadSnapshot("LIVE_STATUS:$tr:$rat:reg=$reg:pci=$pci:earfcn=$ef:band=$band:rsrp=$rsrp:rsrq=$rsrq:sinr=$sinr:dns=$dns:https=$https:recovery=$rec");val text=currentLog();runOnUiThread{output.text=text.takeLast(60000)}}

    private fun stopMonitor(){stopService(Intent(this,NetworkMonitorService::class.java));monitor?.cancel(false);monitor=null;val cm=getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager;try{cm.unregisterNetworkCallback(defaultCallback)}catch(_:Exception){};recoveryCallback?.let{try{cm.unregisterNetworkCallback(it)}catch(_:Exception){}};recoveryCallback=null;cm.bindProcessToNetwork(null);uploadSnapshot("MONITOR_STOPPED_V12");runButton.isEnabled=true;stopButton.isEnabled=false;output.text=currentLog().takeLast(60000)}
    override fun onDestroy(){uiHandler.removeCallbacksAndMessages(null);stopMonitor();shizukuRecovery.close();executor.shutdownNow();uploadExecutor.shutdown();super.onDestroy()}
}
