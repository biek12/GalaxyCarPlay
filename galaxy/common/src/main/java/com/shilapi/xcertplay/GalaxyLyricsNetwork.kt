package com.shilapi.xcertplay

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** 歌词HTTP单连接使用公网网络，不改变进程网络绑定或CarPlay的链路本地路由。 */
internal object GalaxyLyricsNetwork {
    @Volatile private var context: Context? = null

    fun initialize(context: Context) {
        this.context = context.applicationContext
    }

    fun open(url: URL): HttpURLConnection {
        val app = context ?: throw IOException("lyrics_network_not_initialized")
        val manager = app.getSystemService(ConnectivityManager::class.java)
        val candidates = manager.allNetworks.mapNotNull { network ->
            val caps = manager.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
            network to caps
        }
        // 优先系统已验证的Wi-Fi；L6的蜂窝声明来自vlan11，不能据传输名称推断普通UID可用。
        val selected = candidates.maxByOrNull { (_, caps) ->
            (if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) 4 else 0) +
                (if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) 2 else 0)
        } ?: throw IOException("lyrics_internet_network_missing")
        val transport = if (selected.second.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) "cellular" else "physical"
        L7DebugLog.record("Lyrics: stage=network transport=$transport")
        return selected.first.openConnection(url) as HttpURLConnection
    }
}
