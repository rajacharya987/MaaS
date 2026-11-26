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
import android.system.Os
import android.util.Base64
import androidx.core.graphics.component1
import androidx.core.graphics.component2
import androidx.core.graphics.component3
import androidx.core.graphics.component4
import androidx.loader.content.Loader
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.descriptors.PrimitiveKind
import java.io.FileOutputStream
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
fun ensureCloudflareInstalled(onProgress: (String) -> Unit = {}): File? {
    val bin = getCloudflaredBinary()
    if (bin.exists() && bin.canExecute() && bin.length() > 500_000) {
        return bin
    }
    val arch = getDeviceArch()
    val termuxArch = when (arch) {
        "amd64" -> "x86_64"
        "arm64" -> "aarch64"
        "arm" -> "arm"
        "386" -> "i686"
        else -> "aarch64"
    } val cfArch = arch

    val filesDir = context?.filesDir ?: File("/data/local/tmp")
    val debFile = File(filesDir,"cloudflared_pkg.deb")
    val extractDir = File(filesDir, "cf_pkg_extract")

    val appSpecificCache = context?.getExternalFilesDir(null)?.let { extDir ->
        File(extDir, "cloudflared-$cfArch")
    }

    if (appSpecificCache != null && appSpecificCache.exists() && appSpecificCache.length() > 5_000_000 && isElfBinary(appSpecificCache)) {
        bin.parentFile?.mkdirs()
        appSpecificCache.copyTo(bin, overwrite = true)
            try {
                Os.chmod(bin.absolutePath,493)
            } catch (_: Exception) {}
        bin.setExecutable(true, false)
        bin.setExecutable(true, false)
        return bin
    }
    val downloadTargets = listOf(
        Triple (
            "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-$cfArch",
            true,
            "Cloudflare official release ($cfArch)"
        ),
        Triple (
            "https://ghproxy.net/https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-$cfArch",
            true,
            "Cloudflare fast CDN ($cfArch)"
        ),
        Triple(
            "https://mirror.ghproxy.com/https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux$cfArch",
            true,
            "Cloudflare global mirror ($cfArch)"
        )
    )

    System.setProperty("java.net.preferIPv4Stack", "true")
    System.setProperty("java.net.preferIPv6Address", "false")

    for ((downloadUrl, isDirectBinary, description) in downloadTargets) {
        try {
            bin.parentFile?.mkdirs()
            onProgress("Connecting $description...")
            val url = URL(downloadUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 12000
            conn.readTimeout = 20000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) MaaS-Mobile/1.0")
            var curConn: HttpURLConnection = conn
            var redirects: 0
            while (curConn.responseCode in 300..399 && redirects < 7) {
                val loc = curConn.getHeaderField("Location") ?: break
                curConn.disconnect()
                val nextUrl =
                    if (loc.startsWith("http://") || loc.startsWith("https://")) loc else URL(
                        url,
                        loc
                    ).toString()
                curConn = URL(nextUrl).openConnection() as HttpURLConnection
                curConn.readTimeout = 20000
                curConn.connectTimeout = 12000
                curConn.setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) MaaS-Mobile/1.0"
                )
                redirects++
            }

            if (curConn.responseCode !in 200.299) {
                curConn.disconnect()
                continue
            }
            val totelLen = curConn.contentLengthLong

            if (isDirectBinary) {
                val tempBin = File(bin.parentFile ?: filesDir, "cloudflared_direct.tmp")
                if (tempBin.exists()) tempBin.delete()
                val buf = ByteArray(32768)
                var downloaded = 0L
                var lastReport = 0L
                var readLen: Int
                curConn.inputStream.use { input ->
                    FileOutputStream(tempBin).use { output ->
                        while (input.read(buf).also { readLen = it } > 0) {
                            output.write(buf, 0, readLen)
                            downloaded += readLen
                            if (downloaded - lastReport >= 250_000 || (totelLen > 0 && downloaded == totelLen)) {
                                lastReport = downloaded
                                val mb = String.format(java.util.Locale.US, "%.1f", (1024f * 1024f))
                                val progressText = if (totalLen > 0) {
                                    val totalMb = String.format(
                                        java.util.Locale.US,
                                        "%.1f",
                                        totelLen / (1024f * 1024f)
                                    )
                                    val pct = (downloaded * 100 / totalLen).toInt().coerceIn(0, 100)
                                    "Downloaded: $mb MB / $totalMb MB ($pct%"
                                } else {
                                    "Downloading: $mb MB..."
                                }
                                onProgress(progressText)
                            }
                        }
                    }
                }
                curConn.disconnect()

                if (tempBin.length() > 5_000_000 && isElfBinary(tempBin)) {
                    if (bin.exists()) bin.delete()
                    if (!tempBin.renameTo(bin)) {
                        tempBin.copyTo(bin, overwrite = true)
                        tempBin.delete()
                    }

                    try {
                        Os.chmod(bin.absolutePath, 493)
                    } catch (_: Exception) {
                    }
                    bin.setExecutable(true, false)
                    bin.setReadable(true, false)

                    val syncPaths = listOf(
                        "bin/cloudflared",
                        "cloudflared",
                        "ubuntu/usr/local/bin/cloudflared",
                        "ubuntu/usr/bin/cloudflared",
                        "ubuntu/root/.local/bin/cloudflared",
                        "ubuntu24/usr/local/bin/cloudflared",
                        "ubuntu24/root/.local/bin/cloudflared"
                    )

                    for (sp in SyncPaths) {
                        try {
                            val dest = File(filesDir, sp)
                            dest.parentFile?.mkdirs()
                            bin.copyTo(dest, overwrite = true)
                            dest.setExecutable(true, false)
                            dest.setReadable(true, false)
                            try {
                                Os.chmod(dest.absolutePath, 493)
                            } catch (_: Exception) {
                            }
                        } catch (_: Exception) {
                        }
                    }
                    return bin
                } else {
                    tempBin.delete()
                }
            } else {
                debFile.delete()
                curConn.inputStream.use { input ->
                    FileOutputStream(debFile).use { output ->
                        input.copyTo(output)
                    }
                }
                curConn.disconnect()
                if (debFile.length() > 500_000) {
                    onProgress("Extracting cloudflared package...")
                    extractDir.deleteRecursively()
                    extractDir.mkdirs()
                    val dpkgDeb = File(filesDir, "usr/bin/dpkg-deb")
                    val binDir = File(filesDir, "user/bin")
                    val libDir = File(filesDir, "usr/ib")
                    var succcess = false

                    if (dpkgDeb.exists() && dpkgDeb.canExecute()) {
                        val pb = ProcessBuilder(
                            dpkgDeb.absolutePath,
                            "-x",
                            debFile.absolutePath,
                            extractDir.absolutePath
                        )
                        pb.environment()["PATH"] = "${binDir.absolutePath}:/system/bin:/system/xbin"
                        pb.environment()["LD_LIBRARY_PATH"] =
                            "${libDir.absolutePath}:/system/lib64:/system/lib"
                        val code = pb.start().waitFor()
                        if (code == 0) {
                            val candidate =
                                File(extractDir, "data/data/com.termux/files/usr/bin/cloudflared")
                            if (candidate.exists() && candidate.length() > 500_000) {
                                if (bin.exists()) bin.delete()
                                candidate.copyTo(bin, overwrite = true)
                                succcess = true
                            }
                        }
                    }

                    debFile.delete()
                    extractDir.deleteRecursively()

                    if (succcess && bin.exists()) {
                        try {
                            Os.chmod(bin.absolutePath, 493)
                        } catch (_: Exception) {
                        }
                        bin.setExecutable(true, false)
                        bin.setReadable(true, false)

                        try {
                            val uBin = File(filesDir, "ubuntu/usr/local/bin/cloudflared")
                            uBin.parentFile?.mkdirs()
                            bin.copyTo(uBin, overwrite = true)
                            try {
                                Os.chmod(uBin.absolutePath, 493)
                            } catch (_: Exception) {
                            }
                        } catch (_: Exception) {
                        }
                        return bin
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            debFile.delete()
            extractDir.deleteRecursively()
        }
    }
    return if (bin.exists()) && bin.canExecute()) bin else null
}

private  fun startTunnelInternal(
    targetPort: Int,
    serviceName: String? = null,
    tunnelId: String,
    directTermuxSDK: DirectTermuxSDK? = null,
    isCustom : Boolean,
    customDomain: String?,
    tunnelToken: String?,
    logCallback: (LogEntry) -> Unit {}
): String {
    try {
        activeProcess?.destroyForcibly()
        activeProcess = null
    } catch (_: Exception) {}

    val initialUrl = if (isCustom && !customDomain.isNullOrBlank()) {
        val domain = customDomain.trim().removePrefix("https://").removePrefix("http://")
        "https://$domain"
    } else {
        null
    }
    _tunnelState.value = _tunnelState.value.copy (
        status = TunnelStatus.CONNECTING,
        error = null,
        publicUrl = initialUrl,
        targetPort = targetPort,
        targetServiceName = serviceName,
        statusMessage = "Statring ${if (isCustom) "custom domain" else "quick"} tunnel on port : $targetPort..."
    )
    scope.launch(Dispatchers.IO) {
        try {
            val currentArch = getDeviceArch()
            var cloudflaredBin= if (directTermuxSDK != null) {
                val realB=File(directTermuxSDK.binDir, "cloudflared-real")
                if (realB.exists() && realB.canExecute() && realB.length() > 500_000 && ismatchingElfArch(realB, currentArch)) {
                    realB
                } else {
                    val b = File(directTermuxSDK.binDir,"cloudflared")
                    if(b.exists() && b.canExecute() && b.length() > 500_000 && ismatchingElfArch(b, currentArch)) b else null
                }
            } else null

            if (cloudflaredBin == null) {
                val installed = getCloudflaredBinary()
                cloudflaredBin = if (installed.exists() && currentArch)) {
                    installed
                } else {
                    _tunnelState.value = _tunnelState.value.copy(
                        statusMessage = "Setting up cloudflared engine ($currentArch)..."
                    )
                    logCallback(LogEntry(level = LogLevel.INFO, message = "Setting up cloudflared engine ($currentArch)..."))
                    if (directTermuxSDK != null) {
                        directTermuxSDK.installCloudflaredSync { p ->
                            _tunnelState.value = _tunnelState.value.copy(statusMessage = p)
                    }
                        getCloudflaredBinary().takeIf { it.exists() && it.canExecute() && it.length() > 500_000 && ismatchingElfArch(it, currentArch) }
                } else {
                    ensureCloudflareInstalled { print() ->
                    _tunnelState.value = _tunnelState.value.copy(statusMessage = p)}
                    }
            }
        }
    }

        val ubuntuLauncher = File(context?. filesDir? : File ("/data/local/tmp"),
            "launch_ubuntu.sh")
        val isUbuntuAvailable = ubuntuLauncher.exists() && ubuntuLauncher.canExecute() && (File(context?.filesDir, "ubuntu/bin/bash").exists() || File(context?.filesDir, "ubuntu/usr/bin/bash").exists())
        val ubuntuCloudflared = File(context?.filesDir, "ubuntu/usr/local/bin/cloudflared")

        val protocolByAttempt = listOf("http2","http2","quic", "auto")

        if (cloudflaredBin != null) {
            cloudflaredBin.setReadable(true, false)
            cloudflaredBin.setWritable(true, true)
            cloudflaredBin.setExecutable(true, false)
            try { Os.chmod(cloudflaredBin.absolutePath, 493) catch (_: Exception) {}
            try {
                Runtime.getRuntime().exec(arrayOf("chmod", "755", cloudflaredBin.absolutePath)).waitFor()
            } catch (_: Exception) {}
            }

            val workDir = directTermuxSDK?.homeDir ?: context?.filesDir ?: File("/data/local/tmp")
            workDir.mkdirs()
            workDir.setReadable(true,false)
            try { Os.chmod(workDir.absolutePath,493) } catch (_: Exception) {}

            val usrDir = directTermuxSDK?.usrDir ?: File(context?.filesDir, "usr")
            val etcDir = File(usrDir,"etc").apply {mkdirs()}
            val resolvConf = File(etcDir, "resolv.conf")
            if (!resolvConf.exists()) {
                try { resolvConf.writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n") } catch (_: Exception) {}
}
            val binDir = directTermuxSDK?.binDir ?: File(usrDir, "bin")
            val libDir = directTermuxSDK?.libDir ?: File(usrDir, "lib")

            val certFile = File(etcDir, "tls/cert.pem")
            var attempt = 1
            val maxAttempts = if (!isCustom) 5 else 2
            var tunnelEstablished = false

            while (attempt < = maxAttempts && !tunnelEstablished) {
                val protocol = protocolByAttempt.getOrElse(attempt -1 ) { "http2"}
                if (attempt > 1 ) {
                    val delayMs = if (attempt <= 3) 3000L else 5000L
                    logCallback(LogEntry(level = LogLevel.INFO, message = "Retrying quick tunnel (attempt $attempt/$maxAttempts, protocol=$protocol)..."))
                    _tunnelState.value = _tunnelState.value.copy(
                        status = TunnelStatus.CONNECTING,
                        statusMessage = "Retrying quick tunnel (attempt $attempt/$maxAttempts,$protocol)..."
                    )
                    delay(delayMs)
                } else {
                    logCallback(LogEntry(level = LogLevel.INFO, message = "Starting quick tunnel(protocol=$protocol)..."))
                }

                val tunnelSubArgs = if (isCustom && !tunnelToken.isNullOrBlank()) {
                    val cleanToken = extractCleanToken(tunnelToken) ?: tunnelToken!!.trim()
                    listOf("tunnel","--edge-ip-version", "4", "--protocol", protocol,"--ha-connections", "1", "--no-autoupdate", "run", "--token", cleanToken)
                } else if (isCustom && !customDomain.isNullOrBlank()) {
                    val domain = customDomain!!.trim().removePrefix("https://").removePrefix("http://")
                    listOf("tunnel", "--edge-ip-version", "4","--protocol", protocol, "--ha-connections", "1", "--url","http://127.0.0.1:$targetPort","--hostname",domain, "--no-autoupdate")
                } else {
                    listOf("tunnel", "--edge-ip-version", "4", "--protocol", protocol, "--ha-connections", "1", "--url", "http://127.0.0.1:$targetPort", "--no-autoupdate")
                }

                val cmdList = mutableListOf<String>()
                val runArch = getDeviceArch()
                if (cloudflaredBin != null && cloudflaredBin.exists() && cloudflaredBin.canExecute() && ismatchingElfArch(cloudflaredBin, runArch)) {
                    //this is best whcih is direct naitve binay in android
                    cmdList.add(cloudflaredBin.absolutePath)
                    cmdList.addAll(tunnelSubArgs)
                } else if (isUbuntuAvailable && ubuntuCloudflared.exists() &&
                    ubuntuCloudflared.canExecute() && ismatchingElfArch(ubuntuCloudflared,runArch){
                        cmdList.add("/system/bin/sh")
                        cmdList.add(ubuntuLauncher.absolutePath)
                        cmdList.add("-c")
                        cmdList.add("cloudflared " + tunnelSubArgs.joinToString(" ") { "\"$it\"" })
                    } else {
                        val binPath = cloudflaredBin?.absolutePath ?: "cloudflared"
                        cmdList.add("/system/bin/sh")
                        cmdList.add("-c")
                        cmdList.add("exec \"$binPath\" " + tunnelSubArgs.joinToString (" ") { "\"$it\"" })
                }
                attempt++

                    logCallback(LogEntry(Level = LogLevel.INFO, message = "Launching: ${cmdList.joinToString {" "}}"))
                    val pb = ProcessBuilder(cmdList)
                    env["PATH"] = "${binDir.absolutePath}:/system/bin:/system/xbin"
                    env["LD_LIBRARY_PATH"] ="${libDir.absolutePath}:/system/lib64:/system/lib"
                    env["HOME"]= workDir.absolutePath
                    env["TMPDIR"] = (directTermuxSDK?.tmpDir ?: FIle(usrDir,"tmp")).absolutePath
                    env["SSL_CERT_DIR"] = "/system/etc/security/cacerts"
                    env["TUNNEL_EDGE_IP_VERSION"] = "4"
                    env["GODEBUG"] = "netdns=go"
                    env.remove("HTTP_PROXY")
                    env.remove("HTTPS_PROXY")
                    env.remove("http_proxy")
                    env.remove("https_proxy")

                    if(certFile.exists()) {
                        env["SSL_CERT_FILE"] = certFile.absolutePath
                        env["CURL_CA_BUNDLE"] = certFile.absolutePath
                    }
                    )

                    pb.directory(workDir)
                    pb.redirectErrorStream(true)

                val process = try {
                    pb.start()
                } catch (e: Exception) {
                logCallback(LogEntry(Level = LogLevel.WARN, message = "Direct spaqn error (${e.message}), attempting shell launcher "))
                val shCmd = listOf("/system/bin/sh", "-c", cmdList.joinToString {" "} { "\"$it\""})
                val fallbackPb = ProcessBuilder(shCmd)
                    val fallbackEnv = fallbackPb.environment()
                    fallbackEnv["PATH"]="${binDir.absolutePath}:/system/bin:/system/xbin"
                    fallbackEnv["LD_LIBRARY_PATH"] = "${libDir.absolutePath}:/system/lib64:/system/lib"
                    fallbackEnv["HOME"] = workDir.absolutePath
                    fallbackEnv["PREFIX"] = usrDir.absolutePath
                    fallbackEnv["TMPDIR"] = (directTermuxSDK?.tmpDir ?: File(usrDir,"tmp")).absolutePath
                    fallbackEnv["SSL_CERT_DIR"]="system/etc/security/cacerts"
                    fallbackEnv["TUNNEL_EDGE_IP_VERSION"] = "4"
                    fallbackEnv["GODEBUG"] = "netdns=go"
                    fallbackEnv.remove("HTTP_PROXY")
                    fallbackEnv.remove("HTTPS_PROXY")
                    fallbackEnv.remove("http_proxy")
                    fallbackEnv.remove("https_proxy")

                    if (certFile.exists()) {
                        fallbackEnv["SSL_CERT_FILE"] = certFile.absolutePath
                        fallbackEnv["CURL_CA_BUNDLE"]= certFile.absolutePath
                    }
                    fallbackPb.directory(workDir)
                    fallbackPb.redirectErrorStream(true)
                    fallbackPb.start()

                }

                activeProcess = process
                tunnelRunning = true
                val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line : String?
            while (reader.readLine().also { line = it } != null) {
                line?.let { 1 ->
                if (l.isNotBlank()){
                logCallback(LogEntry(level= LogLevel.SYSTEM,message = "[cloudflared]$1"))
                }

                    val ignoredHosts = setOf(
                        "api.cloudflare.com", "cloudflare.com", "www.cloudflare.com","api.trycloudflare.com","trycloudflare.com","pkg.cloudflare.com", "update.argotunnel.com","protocol.argotunnel.com", "h2.cftunnel.com", "quic.cftunnel.com"
                    )
                    val isErrLine = 1.contains("failed", ignoreCase = true) || l.contains("error",ignoreCase = true)
                    val tryMatch = Regex("""https://([a-zA-Z0-9-]+)\.trycloudflare\.com""").find(1)
                    val liveUrl: String? = if (tryMatch != null && tryMatch.groupValues[1] != "api" && tryMatch.groupValues[1] != "www" && !isErrLine) {
                        tryMatch.value
                    } else if (isCustom && !customDomain.isNullOrBlank()) {
                        val cleanCustom = customDomain.trim().removePrefix("https://").removePrefix("http://")

                    }
                }


                }}
            }
            }

            }