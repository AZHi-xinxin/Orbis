package me.rerere.rikkahub.service

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.lover.connect.LcExternalRecoveryGate
import kotlinx.coroutines.*
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.orbis.tabletop.TabletopConversationHost

/** Visible lifetime for an explicitly joined game's companion listener. */
class TabletopCompanionService:Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var lifetime:Job?=null
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(!LcExternalRecoveryGate.isAllowed()){stopSelf();return START_NOT_STICKY}
        val host=TabletopConversationHost.get(this)
        if(intent?.action==PAUSE){host.pauseAll();stopSelf();return START_NOT_STICKY}
        if(!host.hasAutomatic()){stopSelf();return START_NOT_STICKY}
        val manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL,"圆桌同伴连接",NotificationManager.IMPORTANCE_LOW))
        val open=PendingIntent.getActivity(this,ID,Intent(this,RouteActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val pause=PendingIntent.getService(this,ID,Intent(this,TabletopCompanionService::class.java).setAction(PAUSE),PendingIntent.FLAG_IMMUTABLE)
        val notice=NotificationCompat.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_stat_orbis)
            .setContentTitle("圆桌进行中").setContentText("自动接收本局提醒 · 断线自动重联")
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open).addAction(0,"暂停本局 AI",pause).build()
        try {
            if(Build.VERSION.SDK_INT>=34)ServiceCompat.startForeground(this,ID,notice,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(ID,notice)
        } catch(error:RuntimeException) {
            android.util.Log.w("TabletopCompanion","Background lifetime unavailable: ${error.javaClass.simpleName}")
            stopSelf();return START_NOT_STICKY
        }
        if(lifetime?.isActive!=true)lifetime=scope.launch {
            while(isActive && host.hasAutomatic())delay(5000)
            stopSelf()
        }
        return START_STICKY
    }
    override fun onDestroy(){scope.cancel();stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy()}
    companion object {
        private const val CHANNEL="orbis_tabletop_companion"
        private const val ID=2031
        private const val PAUSE="org.orbis.tabletop.PAUSE"
        fun start(context:Context):Boolean {
            if(!LcExternalRecoveryGate.isAllowed())return false
            return runCatching { ContextCompat.startForegroundService(context,Intent(context,TabletopCompanionService::class.java));true }.getOrDefault(false)
        }
    }
}
