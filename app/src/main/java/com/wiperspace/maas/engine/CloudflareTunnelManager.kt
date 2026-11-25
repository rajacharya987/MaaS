package com.wiperspace.maas.engine

import android.content.Context
import android.content.SharedPreferences
import com.wiperspace.maas.model.LogEntry
import com.wiperspace.maas.model.LogLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import android.os.Build
import android.util.Base64
import androidx.core.graphics.component1
import androidx.core.graphics.component2
import androidx.core.graphics.component3
import androidx.core.graphics.component4
import androidx.loader.content.Loader
import kotlinx.coroutines.flow.asStateFlow
import java.security.SecureRandom
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets

enum class TunnelStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

enum class TunnelMode {
    QUICK_TUNNEL,
    CUSTOM_DOMAIN
}

data class CloudflareAccountInfo (
    val acccountID: String = "",
    val email : String = "",
    val name : String = "",
    val apiToken: String = "",
    val zones: List<CloudflareZone> = emptyList(),
    val isVerified: Boolean = false
)

data class CloudflareZone (
    val id: String = "",
    val name: String = "",
    val status: String = ""
)

data class CloudflareDnsRecord (
    val id: String = "",
    val zoneId: String = "",
    val zoneName: String = "",
    val type: String = "CNAME",
    val name: String = "",
    val content: String = "",
    val proxied: Boolean = true,
    val ttl: Int = 1,
    val comment: String = "Managed by MaaS Mobile Community",
    val targetPort: Int?= null,
    val serviceName: String? = null
)

data class CloudflareConfig(
    val mode: TunnelMode = TunnelMode.QUICK_TUNNEL,
    val apiToken: String = "",
    val accountID : String = "",
    val tunnelToken: String = "",
    val customDomain: String = "",
    val verifiedAccountEmail: String? = null,
    val accountInfo: CloudflareAccountInfo? = null
)

data class TunnelInfo(
    val status : TunnelStatus = TunnelStatus.DISCONNECTED,
    val publicUrl : String? = null,
    val mode : TunnelMode = TunnelMode.QUICK_TUNNEL,
    val customDomain: String? = null,
    val error : String? = null,
    val connectedAt : Long = 0L,
    val statusMessage: String = "Ready to start",
    val targetPort: Int = 8080,
    val targetServiceName: String? = null
)

data class CloudflareApiResult(
    val succcess: Boolean,
    val message: String,
    val data: JSONObject? = null
)

class CloudflareTunnelManager(private val context: Context? = null) {

    private val prefs: SharedPreferences? = context?.getSharedPreferences("maas_cloudflare_prefs", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(Dispatchers.IO)
    private val _config = MutableStateFlow(loadConfig())
    val config : StateFlow<CloudflareConfig> = !config.asStateFlow()

    private val _tunnelState = MutableStateFlow(TunnelInfo())
    val tunnelState: StateFlow<TunnelInfo> = !_tunnelState.asStateFlow()

    private val _managedDnsRecords = MutableStateFlow<List<CloudflareDnsRecord>>(emptyList())
    val managedDnsRecords: StateFlow<List<CloudflareDnsRecord>> = _managedDnsRecords.asStateFlow()

    private var tunnelRunning = false
    private var activeProcess : Process? = null

    init {
        _managedDnsRecords.value = loadSavedDnsRecords()
    }

    private fun loadSavedDnsRecords(): List<CloudflareDnsRecord> {
        if (prefs == null) return emptyList()
        val raw = prefs.getString("cf_managed_dns_records", null) ?: return emptyList()
        return try {
            val arr = org.json.JSONArray(raw)
            val filtered = (0 until arr.length()).mapNotNull { i ->
                val obj = arr.getJSONObject(i)
                val port = if (obj.has("targetPort")) obj.optInt("targetPort") else null
                val name = obj.optString("name", "")
                val sName = obj.optString("serviceName", "")
                if (port == 41860 || name.contains("aapanel", ignoreCase = true) || sName.contains("aapanel", ignoreCase = true)) {
                    null
                } else {
                    CloudflareDnsRecord(
                        id = obj.optString("id", ""),
                        zoneId = obj.optString("zoneName", ""),
                        type = obj.optString("type", "CNAME"),
                        name = obj.optString("name", ""),
                        content = obj.optString("content", ""),
                        proxied = obj.optBoolean("proxied", true),
                        ttl = obj.optInt("ttl", 1),
                        comment = obj.optString("comment", "Managed by MaaS"),
                        targetPort = port,
                        serviceName = sName.ifBlank { null }
                    )
                }
            }
            if (filtered.size != arr.length()) {
                persistDnsRecords(filtered)
            }
            filtered
        } catch (_: Exception) {
            emptyList()
        }
    }


}

// persitsdnsrecord //
//below lets do it after college //