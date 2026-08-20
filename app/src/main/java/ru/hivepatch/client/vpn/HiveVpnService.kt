package ru.hivepatch.client.vpn

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import android.util.Log
import ru.hivepatch.client.HiveApp
import ru.hivepatch.client.MainActivity
import ru.hivepatch.client.R
import ru.hivepatch.client.data.Node
import ru.hivepatch.client.data.Prefs
import ru.hivepatch.client.data.SplitMode

class HiveVpnService : VpnService() {
    private var tun: ParcelFileDescriptor? = null
    private var netCb: ConnectivityManager.NetworkCallback? = null
    private val trafficHandler = Handler(Looper.getMainLooper())
    private val trafficTick = object : Runnable {
        override fun run() {
            VpnBus.addTraffic(XrayCore.queryTraffic())
            trafficHandler.postDelayed(this, 1_500)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                try {
                    notifyFg("выкл")
                } catch (_: Exception) {
                }
                shutdown()
                return START_NOT_STICKY
            }
            ACTION_SWITCH -> {
                val node = nodeFrom(intent) ?: return START_STICKY
                switchTo(node)
                return START_STICKY
            }
            ACTION_REBUILD -> {
                val node = nodeFrom(intent) ?: VpnBus.state.value.node
                if (node == null) return START_STICKY
                rebuildTun(node)
                return START_STICKY
            }
            else -> {
                val node = nodeFrom(intent)
                if (node == null) {
                    VpnBus.log("нет узла")
                    stopSelf()
                    return START_NOT_STICKY
                }
                startVpn(node)
                return START_STICKY
            }
        }
    }

    override fun onRevoke() {
        shutdown()
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun startVpn(node: Node) {
        VpnBus.setStatus(ConnStatus.Connecting, node = node, error = "")
        VpnBus.log("TUN ${node.shortLabel}")
        notifyFg("подключение…")
        try {
            closeTun()
            val pfd = buildTun(node.shortLabel) ?: throw IllegalStateException("VPN не выдан")
            tun = pfd
            XrayCore.start(XrayConfig.tunConfig(node), pfd)
            VpnBus.resetSession()
            VpnBus.setStatus(ConnStatus.On, node = node)
            VpnBus.log("вкл · ${node.shortLabel}")
            notifyFg(node.shortLabel)
            startTraffic()
        } catch (e: Exception) {
            Log.e(TAG, "start", e)
            VpnBus.log("ошибка: ${e.message}")
            VpnBus.setStatus(ConnStatus.Off, node = node, error = e.message ?: "ошибка")
            shutdown()
        }
    }

    private fun rebuildTun(node: Node) {
        VpnBus.log("TUN приложения…")
        try {
            closeTun()
            val pfd = buildTun(node.shortLabel) ?: throw IllegalStateException("VPN не выдан")
            tun = pfd
            XrayCore.start(XrayConfig.tunConfig(node), pfd)
            VpnBus.setStatus(ConnStatus.On, node = node)
            notifyFg(node.shortLabel)
        } catch (e: Exception) {
            Log.e(TAG, "rebuild", e)
            VpnBus.log("TUN: ${e.message}")
        }
    }

    private fun switchTo(node: Node) {
        val pfd = tun
        if (pfd == null) {
            startVpn(node)
            return
        }
        VpnBus.setStatus(ConnStatus.Connecting, node = node)
        VpnBus.log("узел → ${node.shortLabel}")
        try {
            XrayCore.switchConfig(XrayConfig.tunConfig(node), pfd)
            VpnBus.setStatus(ConnStatus.On, node = node)
            VpnBus.log("переключено · ${node.shortLabel}")
            notifyFg(node.shortLabel)
        } catch (e: Exception) {
            VpnBus.log("switch: ${e.message}")
            VpnBus.setStatus(ConnStatus.On, error = e.message ?: "switch")
        }
    }

    private fun shutdown() {
        VpnBus.setStatus(ConnStatus.Stopping)
        stopTraffic()
        stopWatchNetwork()
        try {
            XrayCore.stop()
        } catch (_: Exception) {
        }
        closeTun()
        VpnBus.setStatus(ConnStatus.Off, node = VpnBus.state.value.node)
        VpnBus.log("выкл")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startTraffic() {
        trafficHandler.removeCallbacks(trafficTick)
        trafficHandler.post(trafficTick)
    }

    private fun stopTraffic() {
        trafficHandler.removeCallbacks(trafficTick)
        VpnBus.addTraffic(XrayCore.queryTraffic())
    }

    private fun closeTun() {
        try {
            tun?.close()
        } catch (_: Exception) {
        }
        tun = null
    }

    private fun buildTun(session: String): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession("HivePatch · $session")
            .setMtu(TUN_MTU)
            .addAddress("10.10.14.1", 30)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("1.1.1.1")
            .addDnsServer("8.8.8.8")
            .setBlocking(true)
        try {
            builder.addAddress("fdfe:dcba:9876::1", 126)
            builder.addRoute("::", 0)
        } catch (e: Exception) {
            Log.w(TAG, "ipv6 tun", e)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                builder.allowFamily(OsConstants.AF_INET)
                builder.allowFamily(OsConstants.AF_INET6)
            } catch (_: Exception) {
            }
        }
        bindUnderlying(builder)
        try {
            applySplit(builder)
        } catch (e: Exception) {
            Log.w(TAG, "split", e)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }
        val pfd = builder.establish()
        if (pfd != null) {
            watchNetwork()
            VpnBus.log("TUN mtu $TUN_MTU · IPv4+IPv6")
        }
        return pfd
    }

    private fun bindUnderlying(builder: Builder) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) return
        try {
            val net = getSystemService(ConnectivityManager::class.java)?.activeNetwork ?: return
            builder.setUnderlyingNetworks(arrayOf(net))
        } catch (e: Exception) {
            Log.w(TAG, "underlying", e)
        }
    }

    private fun watchNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        stopWatchNetwork()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                try {
                    setUnderlyingNetworks(arrayOf(network))
                } catch (_: Exception) {
                }
            }

            override fun onLost(network: Network) {
                try {
                    val cur = cm.activeNetwork
                    setUnderlyingNetworks(if (cur != null) arrayOf(cur) else null)
                } catch (_: Exception) {
                }
            }
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
            netCb = cb
            cm.activeNetwork?.let { setUnderlyingNetworks(arrayOf(it)) }
        } catch (e: Exception) {
            Log.w(TAG, "netcb", e)
        }
    }

    private fun stopWatchNetwork() {
        val cb = netCb ?: return
        netCb = null
        try {
            getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb)
        } catch (_: Exception) {
        }
    }

    private fun applySplit(builder: Builder) {
        val selected = Prefs.splitPackages
        when (Prefs.splitMode) {
            SplitMode.ONLY -> {
                var added = 0
                for (pkg in selected) {
                    try {
                        builder.addAllowedApplication(pkg)
                        added++
                    } catch (_: Exception) {
                    }
                }
                if (added == 0) {
                    try {
                        builder.addDisallowedApplication(packageName)
                    } catch (_: Exception) {
                    }
                    VpnBus.log("TUN: список пуст, весь трафик")
                } else {
                    VpnBus.log("TUN только $added приложений")
                }
            }
            SplitMode.BYPASS -> {
                try {
                    builder.addDisallowedApplication(packageName)
                } catch (_: Exception) {
                }
                var n = 0
                for (pkg in selected) {
                    if (pkg == packageName) continue
                    try {
                        builder.addDisallowedApplication(pkg)
                        n++
                    } catch (_: Exception) {
                    }
                }
                VpnBus.log("TUN обход $n приложений")
            }
            else -> {
                try {
                    builder.addDisallowedApplication(packageName)
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun notifyFg(text: String) {
        val n = notification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun notification(text: String): Notification {
        val launch = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, HiveVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val b = Notification.Builder(this, HiveApp.CHANNEL_VPN)
            .setContentTitle("HivePatch")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_hive)
            .setContentIntent(launch)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Выкл", stop).build())
        return b.build()
    }

    companion object {
        private const val TAG = "HiveVpn"
        private const val NOTIF_ID = 17
        private const val TUN_MTU = 1280
        const val ACTION_STOP = "ru.hivepatch.client.STOP"
        const val ACTION_SWITCH = "ru.hivepatch.client.SWITCH"
        const val ACTION_REBUILD = "ru.hivepatch.client.REBUILD"
        const val EXTRA_ID = "id"
        const val EXTRA_NODE_ID = "node_id"
        const val EXTRA_PROFILE = "profile"
        const val EXTRA_NAME = "name"
        const val EXTRA_URI = "uri"
        const val EXTRA_PORT = "port"
        const val EXTRA_NETWORK = "network"
        const val EXTRA_ZONE = "zone"

        fun start(ctx: Context, node: Node) {
            val i = Intent(ctx, HiveVpnService::class.java)
            putNode(i, node)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }

        fun switchNode(ctx: Context, node: Node) {
            val i = Intent(ctx, HiveVpnService::class.java).setAction(ACTION_SWITCH)
            putNode(i, node)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }

        fun rebuild(ctx: Context, node: Node) {
            val i = Intent(ctx, HiveVpnService::class.java).setAction(ACTION_REBUILD)
            putNode(i, node)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }

        fun stop(ctx: Context) {
            val i = Intent(ctx, HiveVpnService::class.java).setAction(ACTION_STOP)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }

        private fun putNode(i: Intent, node: Node) {
            i.putExtra(EXTRA_ID, node.id)
            i.putExtra(EXTRA_NODE_ID, node.nodeId)
            i.putExtra(EXTRA_PROFILE, node.profile)
            i.putExtra(EXTRA_NAME, node.name)
            i.putExtra(EXTRA_URI, node.uri)
            i.putExtra(EXTRA_PORT, node.port)
            i.putExtra(EXTRA_NETWORK, node.network)
            i.putExtra(EXTRA_ZONE, node.zone)
        }

        fun nodeFrom(intent: Intent?): Node? {
            val uri = intent?.getStringExtra(EXTRA_URI) ?: return null
            return Node(
                id = intent.getStringExtra(EXTRA_ID) ?: uri,
                nodeId = intent.getStringExtra(EXTRA_NODE_ID).orEmpty(),
                profile = intent.getStringExtra(EXTRA_PROFILE).orEmpty(),
                name = intent.getStringExtra(EXTRA_NAME).orEmpty(),
                uri = uri,
                port = intent.getIntExtra(EXTRA_PORT, 0),
                network = intent.getStringExtra(EXTRA_NETWORK).orEmpty(),
                zone = intent.getStringExtra(EXTRA_ZONE) ?: "intl",
            )
        }
    }
}
