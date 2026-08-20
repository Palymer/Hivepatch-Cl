package ru.hivepatch.client.vpn

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import ru.hivepatch.client.data.Node

object XrayCore {
    private const val TAG = "HiveXray"
    private var controller: CoreController? = null
    @Volatile var running: Boolean = false
        private set

    fun init(ctx: Context) {
        val dir = ctx.filesDir.absolutePath
        Libv2ray.initCoreEnv(dir, "")
        Log.i(TAG, Libv2ray.checkVersionX())
    }

    fun version(): String = try {
        Libv2ray.checkVersionX()
    } catch (_: Exception) {
        "Xray"
    }

    @Synchronized
    fun start(configJson: String, tun: ParcelFileDescriptor) {
        stop()
        val core = Libv2ray.newCoreController(object : CoreCallbackHandler {
            override fun startup(): Long = 0
            override fun shutdown(): Long = 0
            override fun onEmitStatus(code: Long, message: String?): Long {
                Log.i(TAG, "core: $code $message")
                return 0
            }
        })
        core.startLoop(configJson, tun.fd)
        controller = core
        running = true
    }

    @Synchronized
    fun switchConfig(configJson: String, tun: ParcelFileDescriptor) {
        val core = controller
        if (core == null) {
            start(configJson, tun)
            return
        }
        try {
            core.stopLoop()
        } catch (e: Exception) {
            Log.w(TAG, "stop before switch", e)
        }
        running = false
        core.startLoop(configJson, tun.fd)
        running = true
    }

    @Synchronized
    fun stop() {
        val core = controller ?: return
        try {
            core.stopLoop()
        } catch (e: Exception) {
            Log.w(TAG, "stopLoop", e)
        }
        controller = null
        running = false
    }

    fun measureMs(node: Node, probeUrl: String): Long? {
        return try {
            val cfg = XrayConfig.probeConfig(node)
            val ms = Libv2ray.measureOutboundDelay(cfg, probeUrl)
            if (ms > 0) ms else null
        } catch (e: Exception) {
            Log.d(TAG, "probe fail ${node.shortLabel}: ${e.message}")
            null
        }
    }

    @Synchronized
    fun queryTraffic(): Long {
        val core = controller ?: return 0L
        return try {
            core.queryStats("proxy", "uplink") + core.queryStats("proxy", "downlink")
        } catch (e: Exception) {
            Log.d(TAG, "stats: ${e.message}")
            0L
        }
    }
}
