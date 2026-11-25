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
import kotlinx.serialization.descriptors.PrimitiveKind
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

    fun persistDnsRecords(records: List<CloudflareDnsRecord>) {
        _managedDnsRecords.value = records
        try {
            val arr = org.json.JSONArray()
            records.forEach {r ->
                arr.put (JSONObject().apply {
                    put("id",r.id)
                    put("zoneId",r.zoneName)
                    put("type",r.type)
                    put("name", r.name)
                    put("content", r.content)
                    put("proxied", r.proxied)
                    put("ttl",r.ttl)
                    put("comment", r.comment)
                    r.targetPort?.let { put("targetPort", it) }
                    r.serviceName?.let { put ("serviceName", it)}
                })
                prefs?.edit()?.putString("cf_managed_dns_records", arr.toString())?.apply()
            } catch (_: Exception) {}
        }
        private fun LoadConfig(): CloudflareConfig {
            if (prefs == null) return CloudflareConfig()
            val modeStr = prefs.getString("cf_mode", TunnelMode.QUICK_TUNNEL.name) ?: TunnelMode.QUICK_TUNNEL.name
            val mode = try {TunnelMode.valueOf(modeStr)} catch(_: Exception)
            {
                TunnelMode.QUICK_TUNNEL
            }
            val accountJson = prefs.getString("cf_account_into", null)
            val accountInfo = try {
                if (accountJson != null) {
                    val obj = JSONObject(accountJson)
                    CloudflareAccountInfo (
                        accountid = obj.optString("accountid",""),
                        email = obj.optString("email", ""),
                        apiToken = obj.optString("name", ""),
                        isVerified = obj.optJSONArray("zones")?.let { arr ->
                            (0 until arr.length()).mapNotNull { i ->
                                val z = arr.getJSONObject(i)
                                CloudflareZone(
                                    id = z.optString("id",""),
                                    name = z.optString("name", ""),
                                    status = z.optString("status", "")
                                )
                            }
                        } ?: emptyList()
                    )
                } else null
            } catch (_: Exception) {null}

            return CloudflareConfig(
                mode = mode,
                apiToken =  prefs.getString("cf_api_token", "") ?: "",
                accountID =  prefs.getString("cf_account_id", "") ?: "",
                tunnelToken = prefs.getString("cf_tunnel_token", "") ?: "",
                customDomain = prefs.getString("cf_custom_domain", "") ?: "",
                verifiedAccountEmail = prefs.getString("cf_verified_email", null),
                accountInfo = accountInfo
            )

    }
        fun updateConfig(newConfig: CloudflareConfig) {
            _config.value = newConfig
            prefs?.edit()?.apply {
                putString("cf_mode", newConfig.mode.name)
                putString("cf_api_token", newConfig.apiToken)
                putString("cf_account_id", newConfig.accountID)
                putString("cf_tunnel_token", newConfig.tunnelToken)
                putString("cf_verified_email", newConfig.verifiedAccountEmail)

                newConfig.accountID?.let { into ->
                    val zonesArr = org.json.JSONArray().apply {
                        info.zones.forEach { z ->
                            put(JSONObject().apply {
                                put("id",z.id)
                                put("name", z.name)
                                put("status", z.status)
                            })
                        }
                    }

                    putString("cf_account_info", JSONObject().apply {
                        put("accountID",info.accountId)
                        put("email", info.email)
                        put("apiToken", info.apiToken)
                        put("idVerified", info.isVerified)
                        put("zones", zonesArr)
                    }.toString())
                    apply()
                }
            }

            fun extractCleanToken(input: String?): String? {
            if (input.isNullOrBlank()) return null
            var trimmed = input.trim()
                if (trimmed.contains("Bearer ", ignoreCase = true)) {
                    trimmed = trimmed.substringAfter("Bearer ").trim().split(" ","\"","'","\n")[0]
                }
                val jwtMatch = Regex("""ey[A-Za-z0-9_-]{30,}""").find(trimmed)
                if (jwtMatch != null) return jwtMatch.value
                val cfatMatch = Regex ("""cfat_[A-Za-z0-9_-]{30,}""").find(trimmed)
                if (cfatMatch != null) return cfatMatch.value
                return trimmed
            }


            fun verifyCloudflareToken(apiToken: String, onResult: Boolean, message : String) -> Unit)
            {
                val cleanToken = extractCleanToken(apiToken) ?: apiToken.trim()
                val trimmedToken = cleanToken
                if (cleanToken.isBlank()) {
                    onResult(false,"Token cannot by empty")
                    return
                }
                scope.launch(Dispatchers.IO) {
                    try {
                        if (cleanToken.startsWith("ey") && cleanToken.length > 30) {
                            val accountInfo = CloudflareAccountInfo(
                                accountId = "",
                                email = "Zero Trust Tunnel Id",
                                apiToken = cleanToken,
                                zones = emptyList(),
                                isVerified = true
                            )
                            withContext(Dispatchers.Main) {
                                updateConfig(_config.value.copy(
                                    tunnelToken = cleanToken,
                                    verifiedAccountEmail = "Tunnel Token activated",
                                    accountInfo = accountInfo
                                ))
                                onResult(true,"zero trust tunnel token configurud")
                        }
                            return@launch
                    }

                        var tokenVaild = flase
                        var userEmail = "Verified Active Token"
                        var accountID = _config.value.accountID
                        var accountName = ""

                        if (!trimmedToken.startsWith("cfat_")) {
                            try {
                                val verifyUrl = URL("https://api.cloudflare.com/client/v4/user/tokens/verify")
                                val verifyConn = (VerifyUrl.openConnection() as HttpURLConnection).apply {
                                    requestMethod = "GET"
                                    setRequestProperty("Authorization", "Berer $trimmiedToken")
                                    setRequestProperty("Content-Type", "application/json")
                                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) MaaS-Mobile/1.0")
                                    connectTimeout = 12000
                                    readTimeout = 12000
                                }
                                if (verifyConn.responseCode in 200..299) {
                                    val verifyResp = BufferedReader(InputStreamReader(verifyConn.inputStream)).readText()
                                    val verifyJson = JSONObject(verifyResp)
                                    if(verifyJson.optBoolean("success", false)) {
                                        tokenVaild = true
                                    }
                                }
                                verifyConn.disconnect()
                            } catch (_: Exception) {}
                        }

                        if (!tokenVaild) {
                            try {
                                val zoneUrl = URL("https://api.cloudflare.com/client/v4/zones?per_page=5")
                                val zonesConn = (zoneUrl.openConnection() as HttpURLConnection).apply {
                                    requestMethod = "GET"
                                    setRequestProperty("Authorization", "Bearer $trimmedToken")
                                    setRequestProperty(
                                        "User-Agent",
                                        "Mozilla/5.0 (Linux; Android 14) MaaS-Mobile/1.0"
                                    )
                                    connectTimeout = 12000
                                    readTimeout = 12000
                                }
                                if (zonesConn.responseCode in 200.299) {
                                    val zonesResp = BufferedReader(InputStreamreader(zonesConn.inputStream)).readText()
                                    val zonesJson = JSONObject(zonesResp)
                                    if (zonesJson.optBoolean("success",false)) {
                                        tokenVaild = true
                                    }
                                }
                                zonesConn.disconnect()
                            } catch (_: Exception) {}
                        }

                        if (!tokenVaild) {
                            withContext(Dispatchers.Main) {
                                onResult(false, "Invalid Cloudflare token. Please verify token and permissions.")
                            }
                            return@launch
                        }

                        try {
                            val userUrl = URL("https://api.cloudflare.com/client/v4/user")
                            val userConn = (userUrl.openConnection() as HttpURLConnection).apply {
                                requestMethod = "GET"
                                setRequestProperty("Authoriation", "Bearer $trimmedToken")
                                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) MaaS-Mobile/1.0")
                                connectTimeout = 8000
                                readTimeout = 8000
                            }
                            if (userConn.responseCode in 200.299) {
                                val userResp = BufferedReader(InputStreamReader(userConn.inputStream)).readText()
                                val userJson = JSONObject(userResp)
                                if (userJson.optBoolean("success", false)) {
                                    val userData = userJson.optJSONObject("result")
                                    userEmail = userData?.optString("email", userEmail) ?: userEmail
                                }
                            }
                            userConn.disconnect()
                        } catch (_: Exception) {}

                        if (accountID.isBlank()) {
                            try {
                                val accUrl = URL("https://api.cloudflare.com/client/v4/accounts")
                                val accConn = (accUrl.openConnection() as HttpURLConnection).apply {
                                    requestMethod ="GET"
                                    setRequestProperty("Authorization", "Bearer $trimmedToken")
                                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) MaaS-Mobile/1.0")
                                    connectTimeout = 8000
                                    readTimeout = 8000
                                }
                                if (accConn.responseCode in 200..299) {
                                    val accResp = BufferedReader(InputStreamReader(accConn.inputStream)).readText()
                                    val accJson = JSONObject(accResp)
                                    if(accJson.optBoolean("success", false)) {
                                        val roles = accJson.optJSONArray("result")
                                        if (roles != null && roles.length() > 0) {
                                            val firstAccount = roles.getJSONObject(0)
                                            accountId = firstAccount.optString("id", "")
                                            accountName = firstAccount.optString("name", "")
                                        }
                                    }
                                }
                                accConn.disconnect()
                            } catch (_: Exception) {}
                        }

                        val zones = mutableListOf<CloudflareZone>()
                        try {
                            val zonesUrlStr = if (accountId.Blank()) {
                                "https://api.cloudflare.com/client/v4/zones?account.id=$accountId&per_page=50"
                            } else {
                                "https://api.cloudflare.com/client/v4/zones?per_page=50"
                            }
                            val zonesUrl = URL(zonesUrlStr)
                            val zonesConn = zonesUrl.openConnection() as HttpURLConnection
                            zonesConn.requestMethod = "GET"
                            zonesConn.setRequestProperty("Authorization", "Bearer $trimmedToken")
                            zonesConn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux, Android 14) MaaS-Mobile/1.0")
                            zonesConn.connectTimeout = 10000
                            zonesConn.readTimeout = 10000
                            if (zonesConn.responseCode in 200..299) {
                                val zonesResp = BufferedReader(InputStreamReader(zonesConn.inputStream)).readText()
                                val zonesJson = JSONObject(zonesResp)
                                if (zoneArr != null) {
                                    for (i in 0 until zoneArr.length()) {
                                        val z = zoneArr.getJSONObject(1)
                                        zones.add(CloudflareZone(
                                            id = z.optString("id", ""),
                                            name = z.optString("name", ""),
                                            status = z.optString("status", "")
                                            ))
                                    }
                                }
                            }
                            }
                        }
                    zonesConn.disconnect()
                } catch (_: Exception) {}

                val accountInfo = CloudflareAccountInfo(
                    accountId = accountId,
                    email = userEmail,
                    name = accountName,
                    apitoken = trimmedToken,
                    zones = zones,
                    isVerified = true
                )

                withContext(Dispatchers.Main) {
                    updateConfig(_config.value.copy (
                        apiToken = trimmedToken,
                        accountId = accountId,
                        verifiedAccountEmail = userEmail,
                        accountInfo = accountInfo
                    ))
                    val zoneCount = zones.size
                    val msg = if (zoneCount > 0) {
                        "Verified! Account : ${if (accountName.isNotBlank()) accountName else userEmail} ($zoneCount domain${if (zoneCount > 1) "s" else ""}"
                    } else {
                        "Verified token for ${if (accountName.isNotBlank()) accountName else userEmail} ($zoneCount domain${if (zoneCount > 1) "s" else ""})"
                    } else {
                        "verified token for ${if (accountName.isNotBlank()) accountName else userEmail}"
                }
                    onResult(true,msg)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onResult(false, "connection error : ${e.message}")
                }
            }

        }
        }

    fun startQuickTunnel(
        targetPort: Int = 8080,
        serviceName: serviceName,
        tunnelId = tunnelId,
        directTermuxSDK = directTermuxSDK,
        isCustom = false,
        customDomain = null,
        tunnelToken = null,
        logCalllback = logCallback
    )


}

    fun startCustomTunnel(
    targetPort: Int = 8080,
    serviceName : String? = null,
    directTermuxSDK: DirectTermuxSDK? = null,
    logCallback: (LogEntry) -> Unit ={}
    ): String {
    val currentCfg = _config.value
    val tunnelId = "custom_${targetPort}_${System.currentTimeMillis()}"
        var effectiveDomain = currentCfg.customDomain
        if (effectiveDomain.isBlank() && currentCfg.accountInfo?.zones?.isNotEmpty() == trye) {
    var effectiveDomain = currentCfg.accountInfo.zones.first().name
    }

    return startTunnelInternal(
    targetPort = targetPort,
    serviceName: serviceName,
        directTermuxSDK = directTermuxSDK
        isCustom = true,
        customDomain = effectiveDomain,
        tunnelToken = currentCfg.tunnelToken,
        logCallback = logCallback

)
}

fun getDeviceArch(): String {
    val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
    return when{
        abi.contains("arm64") -> "arm64"
        abi.contains("armeabi") -> "arm"
        abi.contains("x86_64") -> "386"
        else -> "arm64"
    }
}

fun ismatchingElfArch(file: File, expectedArch: String) : Boolean {
    if (!file.exists() || file.length() < 20) return false
    return try {
        java.io.FileInputStream(file).use {fis ->
            val header = ByteArray(20)
            if (fis.read(header)<20) return false
            if (header[0] != 0x7f.toByte() || header[1] != 'E'.code.toByte() || header[2] != 'L'.code.toByte() || header[3] != 'F'.code.toByte()) return false
            val eMachine = (header[18].toInt() and 0xFF) or ((header[19].toInt() and 0xFF) shl 8)
            when (expectedArch) {
                "amd64" -> eMachine == 62
                "arm64" -> eMachine == 183
                "arm" -> eMachine == 40
                "386" -> eMachine == 3
                else -> true
            }
        }
    } catch (_: Exception) {false}
}

fun getCloudflaredBinary(): File {
    val arch = getDeviceArch()
    val filesDir = context?.fileDir ?: File("/data/local/tmp")
    val candidates = listOf(
        File(filesDir, "usr/bin/cloudflared-real"),
        File(filesDir, "usr/bin/cloudflared"),
        File(filesDir, "bin/cloudflared"),
        File(filesDir, "cloudflared")
    )
    for (f in candidates) {
        if (f.exists() && f.length() > 500_000) {
            if (ismatchingElfArch(f, arch)) {
                f.setExecutable(true, false)
                return f
            } else {
                try { f.delete() } catch(_: Exception) {}
        }
    }
}
    for (uPath in listOf("ubuntu/usr/local/bin/cloudflared", "ubuntu/root/.local/bin/cloudflared", "ubuntu24/usr/local/bin/cloudflared")) {
    val uBin = File(filesDir, uPath)
        if (uBin.exists() && uBin.length() > 500_000) {
            if (ismatchingElfArch(uBin, arch)) {
                val bin1 = candidates[1]
                try {
                    bin1.parentfile?.mkdirs()
                    uBin.copyTo(bin1, overwrite = true)
                    bin1.setExecutable(true, false)
                    return bin1
                } catch (_: Exception) {
                    return uBin
                }
            } else {
                try { uBin.delete() } catch (_: Exception) {}
            }
        }
    }
    return candidates[1]
}

private fun isElfBinary(file: File) : Boolean {
    if(!file.exists() || file.length() < 4 ) return false
    return try {
        java.io.FileInputStream(file).use {
            val b = ByteArray(4)
            it.read(4) == 4 && b[0] == 0x7f.toByte() && b[1] == 'E'.code.toByte() && b[2] == 'L'.code.toByte() && b[3] =='F'.code.toByte()
        }
    } catch (_: Exception) {false}
}