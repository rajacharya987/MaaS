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
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import android.os.Build
import android.system.Os
import android.util.Base64
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
    QUICK_TUNNEL,    // *.trycloudflare.com (free 1-click)
    CUSTOM_DOMAIN    // Own domain via Cloudflare Zero Trust / Tunnel Token
}

data class CloudflareAccountInfo(
    val accountId: String = "",
    val email: String = "",
    val name: String = "",
    val apiToken: String = "",
    val zones: List<CloudflareZone> = emptyList(),
    val isVerified: Boolean = false
)

data class CloudflareZone(
    val id: String = "",
    val name: String = "",
    val status: String = ""
)

data class CloudflareDnsRecord(
    val id: String = "",
    val zoneId: String = "",
    val zoneName: String = "",
    val type: String = "CNAME", // CNAME, TXT, A, AAAA
    val name: String = "",
    val content: String = "",
    val proxied: Boolean = true,
    val ttl: Int = 1,
    val comment: String = "Managed by MaaS Mobile",
    val targetPort: Int? = null,
    val serviceName: String? = null
)

data class CloudflareConfig(
    val mode: TunnelMode = TunnelMode.QUICK_TUNNEL,
    val apiToken: String = "",
    val accountId: String = "",
    val tunnelToken: String = "",
    val customDomain: String = "",
    val verifiedAccountEmail: String? = null,
    val accountInfo: CloudflareAccountInfo? = null
)

data class TunnelInfo(
    val status: TunnelStatus = TunnelStatus.DISCONNECTED,
    val publicUrl: String? = null,
    val mode: TunnelMode = TunnelMode.QUICK_TUNNEL,
    val customDomain: String? = null,
    val error: String? = null,
    val connectedAt: Long = 0L,
    val statusMessage: String = "Ready to start",
    val targetPort: Int = 8080,
    val targetServiceName: String? = null
)

data class CloudflareApiResult(
    val success: Boolean,
    val message: String,
    val data: JSONObject? = null
)

class CloudflareTunnelManager(private val context: Context? = null) {

    private val prefs: SharedPreferences? = context?.getSharedPreferences("maas_cloudflare_prefs", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(Dispatchers.IO)

    private val _config = MutableStateFlow(loadConfig())
    val config: StateFlow<CloudflareConfig> = _config.asStateFlow()

    private val _tunnelState = MutableStateFlow(TunnelInfo())
    val tunnelState: StateFlow<TunnelInfo> = _tunnelState.asStateFlow()

    private val _managedDnsRecords = MutableStateFlow<List<CloudflareDnsRecord>>(emptyList())
    val managedDnsRecords: StateFlow<List<CloudflareDnsRecord>> = _managedDnsRecords.asStateFlow()

    private var tunnelRunning = false
    private var activeProcess: Process? = null

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
                        zoneId = obj.optString("zoneId", ""),
                        zoneName = obj.optString("zoneName", ""),
                        type = obj.optString("type", "CNAME"),
                        name = obj.optString("name", ""),
                        content = obj.optString("content", ""),
                        proxied = obj.optBoolean("proxied", true),
                        ttl = obj.optInt("ttl", 1),
                        comment = obj.optString("comment", "Managed by MaaS Mobile"),
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
            records.forEach { r ->
                arr.put(JSONObject().apply {
                    put("id", r.id)
                    put("zoneId", r.zoneId)
                    put("zoneName", r.zoneName)
                    put("type", r.type)
                    put("name", r.name)
                    put("content", r.content)
                    put("proxied", r.proxied)
                    put("ttl", r.ttl)
                    put("comment", r.comment)
                    r.targetPort?.let { put("targetPort", it) }
                    r.serviceName?.let { put("serviceName", it) }
                })
            }
            prefs?.edit()?.putString("cf_managed_dns_records", arr.toString())?.apply()
        } catch (_: Exception) {}
    }

    private fun loadConfig(): CloudflareConfig {
        if (prefs == null) return CloudflareConfig()
        val modeStr = prefs.getString("cf_mode", TunnelMode.QUICK_TUNNEL.name) ?: TunnelMode.QUICK_TUNNEL.name
        val mode = try { TunnelMode.valueOf(modeStr) } catch (_: Exception) { TunnelMode.QUICK_TUNNEL }
        val accountJson = prefs.getString("cf_account_info", null)
        val accountInfo = try {
            if (accountJson != null) {
                val obj = JSONObject(accountJson)
                CloudflareAccountInfo(
                    accountId = obj.optString("accountId", ""),
                    email = obj.optString("email", ""),
                    name = obj.optString("name", ""),
                    apiToken = obj.optString("apiToken", ""),
                    isVerified = obj.optBoolean("isVerified", false),
                    zones = obj.optJSONArray("zones")?.let { arr ->
                        (0 until arr.length()).mapNotNull { i ->
                            val z = arr.getJSONObject(i)
                            CloudflareZone(
                                id = z.optString("id", ""),
                                name = z.optString("name", ""),
                                status = z.optString("status", "")
                            )
                        }
                    } ?: emptyList()
                )
            } else null
        } catch (_: Exception) { null }

        return CloudflareConfig(
            mode = mode,
            apiToken = prefs.getString("cf_api_token", "") ?: "",
            accountId = prefs.getString("cf_account_id", "") ?: "",
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
            putString("cf_account_id", newConfig.accountId)
            putString("cf_tunnel_token", newConfig.tunnelToken)
            putString("cf_custom_domain", newConfig.customDomain)
            putString("cf_verified_email", newConfig.verifiedAccountEmail)
            // Serialize account info
            newConfig.accountInfo?.let { info ->
                val zonesArr = org.json.JSONArray().apply {
                    info.zones.forEach { z ->
                        put(JSONObject().apply {
                            put("id", z.id)
                            put("name", z.name)
                            put("status", z.status)
                        })
                    }
                }
                putString("cf_account_info", JSONObject().apply {
                    put("accountId", info.accountId)
                    put("email", info.email)
                    put("name", info.name)
                    put("apiToken", info.apiToken)
                    put("isVerified", info.isVerified)
                    put("zones", zonesArr)
                }.toString())
            }
            apply()
        }
    }

    fun extractCleanToken(input: String?): String? {
        if (input.isNullOrBlank()) return null
        var trimmed = input.trim()
        if (trimmed.contains("Bearer ", ignoreCase = true)) {
            trimmed = trimmed.substringAfter("Bearer ").trim().split(" ", "\"", "'", "\n")[0]
        }
        val jwtMatch = Regex("""ey[A-Za-z0-9_-]{30,}""").find(trimmed)
        if (jwtMatch != null) return jwtMatch.value
        val cfatMatch = Regex("""cfat_[A-Za-z0-9_-]{20,}""").find(trimmed)
        if (cfatMatch != null) return cfatMatch.value
        return trimmed
    }

    /**
     * Verifies a Cloudflare API Token (supports both User Tokens and Account API Tokens prefixed with cfat_)
     * and fetches account + zone information.
     */
    fun verifyCloudflareToken(apiToken: String, onResult: (success: Boolean, message: String) -> Unit) {
        val cleanToken = extractCleanToken(apiToken) ?: apiToken.trim()
        val trimmedToken = cleanToken
        if (cleanToken.isBlank()) {
            onResult(false, "Token cannot be empty")
            return
        }

        scope.launch(Dispatchers.IO) {
            try {
                // If user pasted a Cloudflare Zero Trust Tunnel Token (JWT base64 eyJ...)
                if (cleanToken.startsWith("ey") && cleanToken.length > 30) {
                    val accountInfo = CloudflareAccountInfo(
                        accountId = "",
                        email = "Zero Trust Tunnel Credential",
                        name = "Tunnel Token",
                        apiToken = cleanToken,
                        zones = emptyList(),
                        isVerified = true
                    )
                    withContext(Dispatchers.Main) {
                        updateConfig(_config.value.copy(
                            tunnelToken = cleanToken,
                            verifiedAccountEmail = "Tunnel Token Active",
                            accountInfo = accountInfo
                        ))
                        onResult(true, "Zero Trust Tunnel Token configured!")
                    }
                    return@launch
                }

                var tokenValid = false
                var userEmail = "Verified Active Token"
                var accountId = _config.value.accountId
                var accountName = ""

                // 1. Try user/tokens/verify endpoint (Standard User Tokens)
                if (!trimmedToken.startsWith("cfat_")) {
                    try {
                        val verifyUrl = URL("https://api.cloudflare.com/client/v4/user/tokens/verify")
                        val verifyConn = (verifyUrl.openConnection() as HttpURLConnection).apply {
                            requestMethod = "GET"
                            setRequestProperty("Authorization", "Bearer $trimmedToken")
                            setRequestProperty("Content-Type", "application/json")
                            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) MaaS-Mobile/1.0")
                            connectTimeout = 12000
                            readTimeout = 12000
                        }
                        if (verifyConn.responseCode in 200..299) {
                            val verifyResp = BufferedReader(InputStreamReader(verifyConn.inputStream)).readText()
                            val verifyJson = JSONObject(verifyResp)
                            if (verifyJson.optBoolean("success", false)) {
                                tokenValid = true
                            }
                        }
                        verifyConn.disconnect()
                    } catch (_: Exception) {}
                }

                // 2. Try accounts endpoint (Required for cfat_... Account API Tokens)
                if (!tokenValid) {
                    try {
                        val accUrl = URL("https://api.cloudflare.com/client/v4/accounts")
                        val accConn = (accUrl.openConnection() as HttpURLConnection).apply {
                            requestMethod = "GET"
                            setRequestProperty("Authorization", "Bearer $trimmedToken")
                            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) MaaS-Mobile/1.0")
                            connectTimeout = 12000
                            readTimeout = 12000
                        }
                        if (accConn.responseCode in 200..299) {
                            val accResp = BufferedReader(InputStreamReader(accConn.inputStream)).readText()
                            val accJson = JSONObject(accResp)
                            if (accJson.optBoolean("success", false)) {
                                tokenValid = true
                                val roles = accJson.optJSONArray("result")
                                if (roles != null && roles.length() > 0) {
                                    val firstAccount = roles.getJSONObject(0)
                                    accountId = firstAccount.optString("id", accountId)
                                    accountName = firstAccount.optString("name", "")
                                }
                            }
                        }
                        accConn.disconnect()
                    } catch (_: Exception) {}
                }

                // 3. Try zones endpoint
                if (!tokenValid) {
                    try {
                        val zonesUrl = URL("https://api.cloudflare.com/client/v4/zones?per_page=5")
                        val zonesConn = (zonesUrl.openConnection() as HttpURLConnection).apply {
                            requestMethod = "GET"
                            setRequestProperty("Authorization", "Bearer $trimmedToken")
                            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) MaaS-Mobile/1.0")
                            connectTimeout = 12000
                            readTimeout = 12000
                        }
                        if (zonesConn.responseCode in 200..299) {
                            val zonesResp = BufferedReader(InputStreamReader(zonesConn.inputStream)).readText()
                            val zonesJson = JSONObject(zonesResp)
                            if (zonesJson.optBoolean("success", false)) {
                                tokenValid = true
                            }
                        }
                        zonesConn.disconnect()
                    } catch (_: Exception) {}
                }

                if (!tokenValid) {
                    withContext(Dispatchers.Main) {
                        onResult(false, "Invalid Cloudflare token. Please verify token and permissions.")
                    }
                    return@launch
                }

                // Try to get user email if user endpoint is permitted
                try {
                    val userUrl = URL("https://api.cloudflare.com/client/v4/user")
                    val userConn = (userUrl.openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        setRequestProperty("Authorization", "Bearer $trimmedToken")
                        setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) MaaS-Mobile/1.0")
                        connectTimeout = 8000
                        readTimeout = 8000
                    }
                    if (userConn.responseCode in 200..299) {
                        val userResp = BufferedReader(InputStreamReader(userConn.inputStream)).readText()
                        val userJson = JSONObject(userResp)
                        if (userJson.optBoolean("success", false)) {
                            val userData = userJson.optJSONObject("result")
                            userEmail = userData?.optString("email", userEmail) ?: userEmail
                        }
                    }
                    userConn.disconnect()
                } catch (_: Exception) {}

                // If account ID not yet known, try user accounts
                if (accountId.isBlank()) {
                    try {
                        val accUrl = URL("https://api.cloudflare.com/client/v4/accounts")
                        val accConn = (accUrl.openConnection() as HttpURLConnection).apply {
                            requestMethod = "GET"
                            setRequestProperty("Authorization", "Bearer $trimmedToken")
                            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) MaaS-Mobile/1.0")
                            connectTimeout = 8000
                            readTimeout = 8000
                        }
                        if (accConn.responseCode in 200..299) {
                            val accResp = BufferedReader(InputStreamReader(accConn.inputStream)).readText()
                            val accJson = JSONObject(accResp)
                            if (accJson.optBoolean("success", false)) {
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

                // Step 4: Get zones
                val zones = mutableListOf<CloudflareZone>()
                try {
                    val zonesUrlStr = if (accountId.isNotBlank()) {
                        "https://api.cloudflare.com/client/v4/zones?account.id=$accountId&per_page=50"
                    } else {
                        "https://api.cloudflare.com/client/v4/zones?per_page=50"
                    }
                    val zonesUrl = URL(zonesUrlStr)
                    val zonesConn = zonesUrl.openConnection() as HttpURLConnection
                    zonesConn.requestMethod = "GET"
                    zonesConn.setRequestProperty("Authorization", "Bearer $trimmedToken")
                    zonesConn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) MaaS-Mobile/1.0")
                    zonesConn.connectTimeout = 10000
                    zonesConn.readTimeout = 10000
                    if (zonesConn.responseCode in 200..299) {
                        val zonesResp = BufferedReader(InputStreamReader(zonesConn.inputStream)).readText()
                        val zonesJson = JSONObject(zonesResp)
                        if (zonesJson.optBoolean("success", false)) {
                            val zoneArr = zonesJson.optJSONArray("result")
                            if (zoneArr != null) {
                                for (i in 0 until zoneArr.length()) {
                                    val z = zoneArr.getJSONObject(i)
                                    zones.add(CloudflareZone(
                                        id = z.optString("id", ""),
                                        name = z.optString("name", ""),
                                        status = z.optString("status", "")
                                    ))
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
                    apiToken = trimmedToken,
                    zones = zones,
                    isVerified = true
                )

                withContext(Dispatchers.Main) {
                    updateConfig(_config.value.copy(
                        apiToken = trimmedToken,
                        accountId = accountId,
                        verifiedAccountEmail = userEmail,
                        accountInfo = accountInfo
                    ))
                    val zoneCount = zones.size
                    val msg = if (zoneCount > 0) {
                        "Verified! Account: ${if (accountName.isNotBlank()) accountName else userEmail} ($zoneCount domain${if (zoneCount > 1) "s" else ""})"
                    } else {
                        "Verified token for ${if (accountName.isNotBlank()) accountName else userEmail}"
                    }
                    onResult(true, msg)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onResult(false, "Connection error: ${e.message}")
                }
            }
        }
    }

    /**
     * Starts a Quick Tunnel on the given port (free *.trycloudflare.com URL).
     */
    fun startQuickTunnel(
        targetPort: Int = 8080,
        serviceName: String? = null,
        directTermuxSDK: DirectTermuxSDK? = null,
        logCallback: (LogEntry) -> Unit = {}
    ): String {
        val tunnelId = "quick_${targetPort}_${System.currentTimeMillis()}"
        return startTunnelInternal(
            targetPort = targetPort,
            serviceName = serviceName,
            tunnelId = tunnelId,
            directTermuxSDK = directTermuxSDK,
            isCustom = false,
            customDomain = null,
            tunnelToken = null,
            logCallback = logCallback
        )
    }

    /**
     * Starts a Custom Domain tunnel using Cloudflare Zero Trust credentials.
     */
    fun startCustomTunnel(
        targetPort: Int = 8080,
        serviceName: String? = null,
        directTermuxSDK: DirectTermuxSDK? = null,
        logCallback: (LogEntry) -> Unit = {}
    ): String {
        val currentCfg = _config.value
        val tunnelId = "custom_${targetPort}_${System.currentTimeMillis()}"

        var effectiveDomain = currentCfg.customDomain
        if (effectiveDomain.isBlank() && currentCfg.accountInfo?.zones?.isNotEmpty() == true) {
            effectiveDomain = currentCfg.accountInfo.zones.first().name
        }

        return startTunnelInternal(
            targetPort = targetPort,
            serviceName = serviceName,
            tunnelId = tunnelId,
            directTermuxSDK = directTermuxSDK,
            isCustom = true,
            customDomain = effectiveDomain,
            tunnelToken = currentCfg.tunnelToken,
            logCallback = logCallback
        )
    }

    /**
     * Internal tunnel launcher. Uses cloudflared binary if available in DirectTermuxSDK,
     * or falls back to spawning cloudflared via system shell.
     */
    /**
     * Internal tunnel launcher. Uses cloudflared binary directly or installs it.
     */
    fun getDeviceArch(): String {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        return when {
            abi.contains("arm64") -> "arm64"
            abi.contains("armeabi") -> "arm"
            abi.contains("x86_64") -> "amd64"
            abi.contains("x86") -> "386"
            else -> "arm64"
        }
    }

    fun isMatchingElfArch(file: File, expectedArch: String): Boolean {
        if (!file.exists() || file.length() < 20) return false
        return try {
            java.io.FileInputStream(file).use { fis ->
                val header = ByteArray(20)
                if (fis.read(header) < 20) return false
                if (header[0] != 0x7f.toByte() || header[1] != 'E'.code.toByte() ||
                    header[2] != 'L'.code.toByte() || header[3] != 'F'.code.toByte()) return false
                val eMachine = (header[18].toInt() and 0xFF) or ((header[19].toInt() and 0xFF) shl 8)
                when (expectedArch) {
                    "amd64" -> eMachine == 62 // EM_X86_64
                    "arm64" -> eMachine == 183 // EM_AARCH64
                    "arm" -> eMachine == 40 // EM_ARM
                    "386" -> eMachine == 3 // EM_386
                    else -> true
                }
            }
        } catch (_: Exception) { false }
    }

    fun getCloudflaredBinary(): File {
        val arch = getDeviceArch()
        val filesDir = context?.filesDir ?: File("/data/local/tmp")
        val candidates = listOf(
            File(filesDir, "usr/bin/cloudflared-real"),
            File(filesDir, "usr/bin/cloudflared"),
            File(filesDir, "bin/cloudflared"),
            File(filesDir, "cloudflared")
        )

        for (f in candidates) {
            if (f.exists() && f.length() > 500_000) {
                if (isMatchingElfArch(f, arch)) {
                    f.setExecutable(true, false)
                    return f
                } else {
                    // Stale binary from wrong architecture (e.g. arm64 rootfs on x86_64 emulator)
                    try { f.delete() } catch (_: Exception) {}
                }
            }
        }

        // Check if Ubuntu container has a matching cloudflared binary
        for (uPath in listOf("ubuntu/usr/local/bin/cloudflared", "ubuntu/root/.local/bin/cloudflared", "ubuntu24/usr/local/bin/cloudflared")) {
            val uBin = File(filesDir, uPath)
            if (uBin.exists() && uBin.length() > 500_000) {
                if (isMatchingElfArch(uBin, arch)) {
                    val bin1 = candidates[1]
                    try {
                        bin1.parentFile?.mkdirs()
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

    private fun isElfBinary(file: File): Boolean {
        if (!file.exists() || file.length() < 4) return false
        return try {
            java.io.FileInputStream(file).use {
                val b = ByteArray(4)
                it.read(b) == 4 && b[0] == 0x7f.toByte() && b[1] == 'E'.code.toByte() && b[2] == 'L'.code.toByte() && b[3] == 'F'.code.toByte()
            }
        } catch (_: Exception) { false }
    }

    fun ensureCloudflaredInstalled(onProgress: (String) -> Unit = {}): File? {
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
        }
        val cfArch = arch

        val filesDir = context?.filesDir ?: File("/data/local/tmp")
        val debFile = File(filesDir, "cloudflared_pkg.deb")
        val extractDir = File(filesDir, "cf_pkg_extract")

        // Check app-specific private storage cache first
        val appSpecificCache = context?.getExternalFilesDir(null)?.let { extDir ->
            File(extDir, "cloudflared-$cfArch")
        }
        if (appSpecificCache != null && appSpecificCache.exists() && appSpecificCache.length() > 5_000_000 && isElfBinary(appSpecificCache)) {
            bin.parentFile?.mkdirs()
            appSpecificCache.copyTo(bin, overwrite = true)
            try { Os.chmod(bin.absolutePath, 493) } catch (_: Exception) {}
            bin.setExecutable(true, false)
            bin.setReadable(true, false)
            return bin
        }

        val downloadTargets = listOf(
            Triple(
                "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-$cfArch",
                true,
                "Cloudflare official release ($cfArch)"
            ),
            Triple(
                "https://ghproxy.net/https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-$cfArch",
                true,
                "Cloudflare fast CDN mirror ($cfArch)"
            ),
            Triple(
                "https://mirror.ghproxy.com/https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-$cfArch",
                true,
                "Cloudflare global mirror ($cfArch)"
            )
        )

        System.setProperty("java.net.preferIPv4Stack", "true")
        System.setProperty("java.net.preferIPv6Addresses", "false")

        for ((downloadUrl, isDirectBinary, description) in downloadTargets) {
            try {
                bin.parentFile?.mkdirs()
                onProgress("Connecting: $description...")
                val url = URL(downloadUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 12000
                conn.readTimeout = 20000
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) MaaS-Mobile/1.0")

                var curConn: HttpURLConnection = conn
                var redirects = 0
                while (curConn.responseCode in 300..399 && redirects < 7) {
                    val loc = curConn.getHeaderField("Location") ?: break
                    curConn.disconnect()
                    val nextUrl = if (loc.startsWith("http://") || loc.startsWith("https://")) loc else URL(url, loc).toString()
                    curConn = URL(nextUrl).openConnection() as HttpURLConnection
                    curConn.connectTimeout = 12000
                    curConn.readTimeout = 20000
                    curConn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) MaaS-Mobile/1.0")
                    redirects++
                }

                if (curConn.responseCode !in 200..299) {
                    curConn.disconnect()
                    continue
                }

                val totalLen = curConn.contentLengthLong

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
                                if (downloaded - lastReport >= 250_000 || (totalLen > 0 && downloaded == totalLen)) {
                                    lastReport = downloaded
                                    val mb = String.format(java.util.Locale.US, "%.1f", downloaded / (1024f * 1024f))
                                    val progressText = if (totalLen > 0) {
                                        val totalMb = String.format(java.util.Locale.US, "%.1f", totalLen / (1024f * 1024f))
                                        val pct = (downloaded * 100 / totalLen).toInt().coerceIn(0, 100)
                                        "Downloading: $mb MB / $totalMb MB ($pct%)"
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
                        try { Os.chmod(bin.absolutePath, 493) } catch (_: Exception) {}
                        bin.setExecutable(true, false)
                        bin.setReadable(true, false)

                        // Sync to all bin paths and Ubuntu container if present
                        val syncPaths = listOf(
                            "bin/cloudflared",
                            "cloudflared",
                            "ubuntu/usr/local/bin/cloudflared",
                            "ubuntu/usr/bin/cloudflared",
                            "ubuntu/root/.local/bin/cloudflared",
                            "ubuntu24/usr/local/bin/cloudflared",
                            "ubuntu24/root/.local/bin/cloudflared"
                        )
                        for (sp in syncPaths) {
                            try {
                                val dest = File(filesDir, sp)
                                dest.parentFile?.mkdirs()
                                bin.copyTo(dest, overwrite = true)
                                dest.setExecutable(true, false)
                                dest.setReadable(true, false)
                                try { Os.chmod(dest.absolutePath, 493) } catch (_: Exception) {}
                            } catch (_: Exception) {}
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
                        val binDir = File(filesDir, "usr/bin")
                        val libDir = File(filesDir, "usr/lib")
                        var success = false

                        if (dpkgDeb.exists() && dpkgDeb.canExecute()) {
                            val pb = ProcessBuilder(dpkgDeb.absolutePath, "-x", debFile.absolutePath, extractDir.absolutePath)
                            pb.environment()["PATH"] = "${binDir.absolutePath}:/system/bin:/system/xbin"
                            pb.environment()["LD_LIBRARY_PATH"] = "${libDir.absolutePath}:/system/lib64:/system/lib"
                            val code = pb.start().waitFor()
                            if (code == 0) {
                                val candidate = File(extractDir, "data/data/com.termux/files/usr/bin/cloudflared")
                                if (candidate.exists() && candidate.length() > 500_000) {
                                    if (bin.exists()) bin.delete()
                                    candidate.copyTo(bin, overwrite = true)
                                    success = true
                                }
                            }
                        }

                        debFile.delete()
                        extractDir.deleteRecursively()

                        if (success && bin.exists()) {
                            try { Os.chmod(bin.absolutePath, 493) } catch (_: Exception) {}
                            bin.setExecutable(true, false)
                            bin.setReadable(true, false)

                            try {
                                val uBin = File(filesDir, "ubuntu/usr/local/bin/cloudflared")
                                uBin.parentFile?.mkdirs()
                                bin.copyTo(uBin, overwrite = true)
                                try { Os.chmod(uBin.absolutePath, 493) } catch (_: Exception) {}
                            } catch (_: Exception) {}

                            return bin
                        }
                    }
                }
            } catch (_: Exception) {}
            finally {
                debFile.delete()
                extractDir.deleteRecursively()
            }
        }
        return if (bin.exists() && bin.canExecute()) bin else null
    }

    private fun startTunnelInternal(
        targetPort: Int,
        serviceName: String? = null,
        tunnelId: String,
        directTermuxSDK: DirectTermuxSDK? = null,
        isCustom: Boolean,
        customDomain: String?,
        tunnelToken: String?,
        logCallback: (LogEntry) -> Unit = {}
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

        _tunnelState.value = _tunnelState.value.copy(
            status = TunnelStatus.CONNECTING,
            error = null,
            publicUrl = initialUrl,
            targetPort = targetPort,
            targetServiceName = serviceName,
            statusMessage = "Starting ${if (isCustom) "custom domain" else "quick"} tunnel on port :$targetPort..."
        )

        scope.launch(Dispatchers.IO) {
            try {
                // Step 1: Ensure cloudflared binary is installed and matches host architecture
                val currentArch = getDeviceArch()
                var cloudflaredBin = if (directTermuxSDK != null) {
                    val realB = File(directTermuxSDK.binDir, "cloudflared-real")
                    if (realB.exists() && realB.canExecute() && realB.length() > 500_000 && isMatchingElfArch(realB, currentArch)) {
                        realB
                    } else {
                        val b = File(directTermuxSDK.binDir, "cloudflared")
                        if (b.exists() && b.canExecute() && b.length() > 500_000 && isMatchingElfArch(b, currentArch)) b else null
                    }
                } else null

                if (cloudflaredBin == null) {
                    val installed = getCloudflaredBinary()
                    cloudflaredBin = if (installed.exists() && installed.canExecute() && installed.length() > 500_000 && isMatchingElfArch(installed, currentArch)) {
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
                            getCloudflaredBinary().takeIf { it.exists() && it.canExecute() && it.length() > 500_000 && isMatchingElfArch(it, currentArch) }
                        } else {
                            ensureCloudflaredInstalled { p ->
                                _tunnelState.value = _tunnelState.value.copy(statusMessage = p)
                            }
                        }
                    }
                }

                // Step 2: Build base command (binary path only - args added per-attempt)
                val ubuntuLauncher = File(context?.filesDir ?: File("/data/local/tmp"), "launch_ubuntu.sh")
                val isUbuntuAvailable = ubuntuLauncher.exists() && ubuntuLauncher.canExecute() &&
                    (File(context?.filesDir, "ubuntu/bin/bash").exists() || File(context?.filesDir, "ubuntu/usr/bin/bash").exists())
                val ubuntuCloudflared = File(context?.filesDir, "ubuntu/usr/local/bin/cloudflared")

                // Prefer http2 on emulators/mobile (QUIC/UDP often blocked). Rotate per attempt.
                // Attempt 1,2 → http2 | Attempt 3,4 → quic | Attempt 5 → auto
                val protocolByAttempt = listOf("http2", "http2", "quic", "quic", "auto")

                if (cloudflaredBin != null) {
                    cloudflaredBin.setReadable(true, false)
                    cloudflaredBin.setWritable(true, true)
                    cloudflaredBin.setExecutable(true, false)
                    try { Os.chmod(cloudflaredBin.absolutePath, 493) } catch (_: Exception) {}
                    try {
                        Runtime.getRuntime().exec(arrayOf("chmod", "755", cloudflaredBin.absolutePath)).waitFor()
                    } catch (_: Exception) {}
                }

                val workDir = directTermuxSDK?.homeDir ?: context?.filesDir ?: File("/data/local/tmp")
                workDir.mkdirs()
                workDir.setReadable(true, false)
                workDir.setExecutable(true, false)
                try { Os.chmod(workDir.absolutePath, 493) } catch (_: Exception) {}

                val usrDir = directTermuxSDK?.usrDir ?: File(context?.filesDir, "usr")
                val etcDir = File(usrDir, "etc").apply { mkdirs() }
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

                while (attempt <= maxAttempts && !tunnelEstablished) {
                    val protocol = protocolByAttempt.getOrElse(attempt - 1) { "http2" }
                    if (attempt > 1) {
                        val delayMs = if (attempt <= 3) 3000L else 5000L
                        logCallback(LogEntry(level = LogLevel.INFO, message = "Retrying quick tunnel (attempt $attempt/$maxAttempts, protocol=$protocol)..."))
                        _tunnelState.value = _tunnelState.value.copy(
                            status = TunnelStatus.CONNECTING,
                            statusMessage = "Retrying quick tunnel (attempt $attempt/$maxAttempts, $protocol)..."
                        )
                        delay(delayMs)
                    } else {
                        logCallback(LogEntry(level = LogLevel.INFO, message = "Starting quick tunnel (protocol=$protocol)..."))
                    }

                    // Build per-attempt args with selected protocol
                    val tunnelSubArgs = if (isCustom && !tunnelToken.isNullOrBlank()) {
                        val cleanToken = extractCleanToken(tunnelToken) ?: tunnelToken!!.trim()
                        listOf("tunnel", "--edge-ip-version", "4", "--protocol", protocol, "--ha-connections", "1", "--no-autoupdate", "run", "--token", cleanToken)
                    } else if (isCustom && !customDomain.isNullOrBlank()) {
                        val domain = customDomain!!.trim().removePrefix("https://").removePrefix("http://")
                        listOf("tunnel", "--edge-ip-version", "4", "--protocol", protocol, "--ha-connections", "1", "--url", "http://127.0.0.1:$targetPort", "--hostname", domain, "--no-autoupdate")
                    } else {
                        listOf("tunnel", "--edge-ip-version", "4", "--protocol", protocol, "--ha-connections", "1", "--url", "http://127.0.0.1:$targetPort", "--no-autoupdate")
                    }

                    // Build command list for this attempt - Direct binary invocation FIRST!
                    val cmdList = mutableListOf<String>()
                    val runArch = getDeviceArch()
                    if (cloudflaredBin != null && cloudflaredBin.exists() && cloudflaredBin.canExecute() && isMatchingElfArch(cloudflaredBin, runArch)) {
                        // Direct native binary invocation on Android Bionic - fastest and most reliable!
                        cmdList.add(cloudflaredBin.absolutePath)
                        cmdList.addAll(tunnelSubArgs)
                    } else if (isUbuntuAvailable && ubuntuCloudflared.exists() && ubuntuCloudflared.canExecute() && isMatchingElfArch(ubuntuCloudflared, runArch)) {
                        cmdList.add("/system/bin/sh")
                        cmdList.add(ubuntuLauncher.absolutePath)
                        cmdList.add("-c")
                        cmdList.add("cloudflared " + tunnelSubArgs.joinToString(" ") { "\"$it\"" })
                    } else {
                        // Fallback: shell wrapper
                        val binPath = cloudflaredBin?.absolutePath ?: "cloudflared"
                        cmdList.add("/system/bin/sh")
                        cmdList.add("-c")
                        cmdList.add("exec \"$binPath\" " + tunnelSubArgs.joinToString(" ") { "\"$it\"" })
                    }

                    attempt++

                    logCallback(LogEntry(level = LogLevel.INFO, message = "Launching: ${cmdList.joinToString(" ")}"))

                    val pb = ProcessBuilder(cmdList)
                    val env = pb.environment()
                    env["PATH"] = "${binDir.absolutePath}:/system/bin:/system/xbin"
                    env["LD_LIBRARY_PATH"] = "${libDir.absolutePath}:/system/lib64:/system/lib"
                    env["HOME"] = workDir.absolutePath
                    env["PREFIX"] = usrDir.absolutePath
                    env["TMPDIR"] = (directTermuxSDK?.tmpDir ?: File(usrDir, "tmp")).absolutePath
                    env["SSL_CERT_DIR"] = "/system/etc/security/cacerts"
                    env["TUNNEL_EDGE_IP_VERSION"] = "4"
                    env["GODEBUG"] = "netdns=go"
                    env.remove("HTTP_PROXY")
                    env.remove("HTTPS_PROXY")
                    env.remove("http_proxy")
                    env.remove("https_proxy")
                    if (certFile.exists()) {
                        env["SSL_CERT_FILE"] = certFile.absolutePath
                        env["CURL_CA_BUNDLE"] = certFile.absolutePath
                    }

                    pb.directory(workDir)
                    pb.redirectErrorStream(true)

                    val process = try {
                        pb.start()
                    } catch (e: Exception) {
                        logCallback(LogEntry(level = LogLevel.WARN, message = "Direct spawn error (${e.message}), attempting shell launcher..."))
                        val shCmd = listOf("/system/bin/sh", "-c", cmdList.joinToString(" ") { "\"$it\"" })
                        val fallbackPb = ProcessBuilder(shCmd)
                        val fallbackEnv = fallbackPb.environment()
                        fallbackEnv["PATH"] = "${binDir.absolutePath}:/system/bin:/system/xbin"
                        fallbackEnv["LD_LIBRARY_PATH"] = "${libDir.absolutePath}:/system/lib64:/system/lib"
                        fallbackEnv["HOME"] = workDir.absolutePath
                        fallbackEnv["PREFIX"] = usrDir.absolutePath
                        fallbackEnv["TMPDIR"] = (directTermuxSDK?.tmpDir ?: File(usrDir, "tmp")).absolutePath
                        fallbackEnv["SSL_CERT_DIR"] = "/system/etc/security/cacerts"
                        fallbackEnv["TUNNEL_EDGE_IP_VERSION"] = "4"
                        fallbackEnv["GODEBUG"] = "netdns=go"
                        fallbackEnv.remove("HTTP_PROXY")
                        fallbackEnv.remove("HTTPS_PROXY")
                        fallbackEnv.remove("http_proxy")
                        fallbackEnv.remove("https_proxy")
                        if (certFile.exists()) {
                            fallbackEnv["SSL_CERT_FILE"] = certFile.absolutePath
                            fallbackEnv["CURL_CA_BUNDLE"] = certFile.absolutePath
                        }
                        fallbackPb.directory(workDir)
                        fallbackPb.redirectErrorStream(true)
                        fallbackPb.start()
                    }

                    activeProcess = process
                    tunnelRunning = true
                    val reader = BufferedReader(InputStreamReader(process.inputStream))
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        line?.let { l ->
                            if (l.isNotBlank()) {
                                logCallback(LogEntry(level = LogLevel.SYSTEM, message = "[cloudflared] $l"))
                            }

                            val ignoredHosts = setOf(
                                "api.cloudflare.com", "cloudflare.com", "www.cloudflare.com",
                                "api.trycloudflare.com", "trycloudflare.com",
                                "pkg.cloudflare.com", "update.argotunnel.com", "protocol.argotunnel.com",
                                "h2.cftunnel.com", "quic.cftunnel.com"
                            )

                            val isErrLine = l.contains("failed", ignoreCase = true) || l.contains("error", ignoreCase = true)
                            val tryMatch = Regex("""https://([a-zA-Z0-9-]+)\.trycloudflare\.com""").find(l)
                            val liveUrl: String? = if (tryMatch != null && tryMatch.groupValues[1] != "api" && tryMatch.groupValues[1] != "www" && !isErrLine) {
                                tryMatch.value
                            } else if (isCustom && !customDomain.isNullOrBlank()) {
                                val cleanCustom = customDomain.trim().removePrefix("https://").removePrefix("http://")
                                if ((l.contains(cleanCustom, ignoreCase = true) || l.contains("Registered tunnel connection", ignoreCase = true)) && !isErrLine) {
                                    "https://$cleanCustom"
                                } else null
                            } else {
                                val generalMatch = Regex("""https://([a-zA-Z0-9][a-zA-Z0-9.-]+\.[a-zA-Z]{2,})""").find(l)
                                if (generalMatch != null && !isErrLine) {
                                    val host = generalMatch.groupValues[1].lowercase()
                                    if (host !in ignoredHosts && !host.endsWith(".cftunnel.com") && !host.endsWith(".argotunnel.com") && host != "cloudflare.com") {
                                        generalMatch.value
                                    } else null
                                } else null
                            }

                            if (liveUrl != null) {
                                tunnelEstablished = true
                                _tunnelState.value = _tunnelState.value.copy(
                                    status = TunnelStatus.CONNECTED,
                                    publicUrl = liveUrl,
                                    mode = if (isCustom) TunnelMode.CUSTOM_DOMAIN else TunnelMode.QUICK_TUNNEL,
                                    customDomain = if (isCustom) customDomain else null,
                                    targetPort = targetPort,
                                    targetServiceName = serviceName,
                                    error = null,
                                    connectedAt = System.currentTimeMillis(),
                                    statusMessage = if (isCustom) "Connected to custom domain: $liveUrl" else "Live Quick Tunnel: $liveUrl"
                                )
                                logCallback(LogEntry(level = LogLevel.SUCCESS, message = "Tunnel established: $liveUrl -> localhost:$targetPort (${serviceName ?: "Service"})"))
                            }

                            if (l.contains("fatal", ignoreCase = true) || l.contains("invalid tunnel token", ignoreCase = true)) {
                                if (_tunnelState.value.status != TunnelStatus.CONNECTED) {
                                    _tunnelState.value = _tunnelState.value.copy(
                                        status = TunnelStatus.ERROR,
                                        publicUrl = null,
                                        error = l.trim()
                                    )
                                }
                                logCallback(LogEntry(level = LogLevel.ERROR, message = "[cloudflared error] $l"))
                            }
                        }
                    }

                    val exitCode = process.waitFor()
                    activeProcess = null
                    tunnelRunning = false
                    logCallback(LogEntry(level = LogLevel.INFO, message = "cloudflared exited (code=$exitCode, attempt=${attempt-1}/$maxAttempts)"))

                    if (tunnelEstablished) {
                        break
                    }
                }

                if (!tunnelEstablished) {
                    val lastError = _tunnelState.value.error ?: "Unable to establish Cloudflare edge tunnel. Check logs for details."
                    _tunnelState.value = _tunnelState.value.copy(
                        status = TunnelStatus.ERROR,
                        publicUrl = null,
                        error = lastError,
                        statusMessage = "Tunnel failed to connect"
                    )
                    logCallback(LogEntry(level = LogLevel.ERROR, message = "Cloudflare tunnel failed: $lastError"))
                } else if (_tunnelState.value.status != TunnelStatus.CONNECTED) {
                    _tunnelState.value = _tunnelState.value.copy(
                        status = TunnelStatus.DISCONNECTED,
                        publicUrl = null,
                        statusMessage = "Tunnel process ended"
                    )
                }
            } catch (e: Exception) {
                activeProcess = null
                tunnelRunning = false
                val err = e.message ?: "Tunnel launch failed"
                _tunnelState.value = _tunnelState.value.copy(
                    status = TunnelStatus.ERROR,
                    publicUrl = null,
                    error = err,
                    statusMessage = "Tunnel error: $err"
                )
                logCallback(LogEntry(level = LogLevel.ERROR, message = "Tunnel error: $err"))
            }
        }

        return _tunnelState.value.publicUrl ?: "Establishing tunnel..."
    }

    fun stopTunnel(directTermuxSDK: DirectTermuxSDK? = null, logCallback: (LogEntry) -> Unit = {}) {
        try {
            activeProcess?.destroyForcibly()
            activeProcess = null
        } catch (_: Exception) {}
        if (tunnelRunning) {
            scope.launch(Dispatchers.IO) {
                try {
                    val p = ProcessBuilder("sh", "-c", "pkill cloudflared 2>/dev/null; true").start()
                    p.waitFor()
                } catch (_: Exception) {}
            }
            directTermuxSDK?.execute("pkill cloudflared 2>/dev/null; true")
        }
        tunnelRunning = false
        _tunnelState.value = TunnelInfo(
            status = TunnelStatus.DISCONNECTED,
            publicUrl = null,
            mode = _config.value.mode,
            targetPort = _tunnelState.value.targetPort,
            targetServiceName = _tunnelState.value.targetServiceName,
            statusMessage = "Tunnel stopped"
        )
        logCallback(LogEntry(level = LogLevel.INFO, message = "Cloudflare Tunnel disconnected"))
    }

    fun isConnected(): Boolean {
        return _tunnelState.value.status == TunnelStatus.CONNECTED
    }

    /**
     * Returns the list of zones registered to the verified Cloudflare account.
     */
    fun getAvailableZones(): List<CloudflareZone> {
        return _config.value.accountInfo?.zones ?: emptyList()
    }

    /**
     * Fetches DNS records for a given Cloudflare zone via API v4.
     */
    fun fetchDnsRecords(
        zoneId: String,
        onResult: (success: Boolean, records: List<CloudflareDnsRecord>, message: String) -> Unit
    ) {
        val token = _config.value.apiToken.trim()
        if (token.isBlank()) {
            onResult(false, emptyList(), "Cloudflare API Token not configured")
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                val url = URL("https://api.cloudflare.com/client/v4/zones/$zoneId/dns_records?per_page=100")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("Authorization", "Bearer $token")
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) MaaS-Mobile/1.0")
                    connectTimeout = 12000
                    readTimeout = 12000
                }
                val code = conn.responseCode
                val resp = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText() ?: ""
                conn.disconnect()
                val json = JSONObject(resp)
                if (json.optBoolean("success", false)) {
                    val results = json.optJSONArray("result")
                    val records = mutableListOf<CloudflareDnsRecord>()
                    if (results != null) {
                        for (i in 0 until results.length()) {
                            val obj = results.getJSONObject(i)
                            records.add(
                                CloudflareDnsRecord(
                                    id = obj.optString("id", ""),
                                    zoneId = obj.optString("zone_id", zoneId),
                                    zoneName = obj.optString("zone_name", ""),
                                    type = obj.optString("type", "CNAME"),
                                    name = obj.optString("name", ""),
                                    content = obj.optString("content", ""),
                                    proxied = obj.optBoolean("proxied", false),
                                    ttl = obj.optInt("ttl", 1),
                                    comment = obj.optString("comment", "")
                                )
                            )
                        }
                    }
                    withContext(Dispatchers.Main) {
                        val currentManaged = _managedDnsRecords.value.toMutableList()
                        records.forEach { fetched ->
                            val idx = currentManaged.indexOfFirst { it.name.equals(fetched.name, ignoreCase = true) && it.type.equals(fetched.type, ignoreCase = true) }
                            if (idx >= 0) {
                                currentManaged[idx] = fetched.copy(
                                    targetPort = currentManaged[idx].targetPort,
                                    serviceName = currentManaged[idx].serviceName
                                )
                            } else {
                                currentManaged.add(fetched)
                            }
                        }
                        persistDnsRecords(currentManaged)
                        onResult(true, records, "Found ${records.size} DNS record(s) on Cloudflare")
                    }
                } else {
                    val errors = json.optJSONArray("errors")
                    val errMsg = if (errors != null && errors.length() > 0) errors.getJSONObject(0).optString("message", "Failed to fetch DNS records") else "Failed to fetch DNS records"
                    withContext(Dispatchers.Main) {
                        onResult(false, emptyList(), errMsg)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onResult(false, emptyList(), "Network error: ${e.message}")
                }
            }
        }
    }

    /**
     * Fetches DNS records for a domain name by auto-resolving its zone ID.
     */
    fun fetchDnsRecordsForDomain(domainName: String, onResult: (Boolean, List<CloudflareDnsRecord>, String) -> Unit) {
        val token = _config.value.apiToken.trim()
        if (token.isBlank()) {
            onResult(false, emptyList(), "Cloudflare API Token not configured")
            return
        }
        scope.launch(Dispatchers.IO) {
            var zoneId = _config.value.accountInfo?.zones?.find { it.name.equals(domainName, ignoreCase = true) }?.id ?: ""
            if (zoneId.isBlank()) {
                try {
                    val zUrl = URL("https://api.cloudflare.com/client/v4/zones?name=${domainName.trim()}")
                    val zConn = (zUrl.openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        setRequestProperty("Authorization", "Bearer $token")
                        setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) MaaS-Mobile/1.0")
                        connectTimeout = 8000
                        readTimeout = 8000
                    }
                    if (zConn.responseCode in 200..299) {
                        val zResp = zConn.inputStream.bufferedReader().readText()
                        val zJson = JSONObject(zResp)
                        val zResults = zJson.optJSONArray("result")
                        if (zResults != null && zResults.length() > 0) {
                            zoneId = zResults.getJSONObject(0).optString("id", "")
                        }
                    }
                    zConn.disconnect()
                } catch (_: Exception) {}
            }
            if (zoneId.isBlank()) {
                withContext(Dispatchers.Main) {
                    onResult(false, emptyList(), "Domain $domainName not found in your Cloudflare account")
                }
                return@launch
            }
            fetchDnsRecords(zoneId, onResult)
        }
    }

    /**
     * Creates or updates a DNS record (CNAME, TXT, A) on Cloudflare.
     * Automatically resolves the Zone ID from the domain if not provided.
     */
    fun createOrUpdateDnsRecord(
        zoneId: String,
        record: CloudflareDnsRecord,
        onResult: (success: Boolean, record: CloudflareDnsRecord?, message: String) -> Unit
    ) {
        val token = _config.value.apiToken.trim()
        if (token.isBlank()) {
            // Local fallback when no API token exists
            val localRecord = record.copy(
                id = record.id.ifBlank { "local-${System.currentTimeMillis()}" },
                zoneId = zoneId.ifBlank { "local-zone" }
            )
            val updated = _managedDnsRecords.value.filter { it.name != localRecord.name || it.type != localRecord.type }.toMutableList()
            updated.add(0, localRecord)
            persistDnsRecords(updated)
            onResult(true, localRecord, "Subdomain configured locally. (Add Cloudflare API Token for remote DNS sync)")
            return
        }

        scope.launch(Dispatchers.IO) {
            try {
                // Auto-resolve zone ID if missing
                var targetZoneId = zoneId
                if (targetZoneId.isBlank() || targetZoneId == "local-zone") {
                    val cached = _config.value.accountInfo?.zones?.find {
                        it.name.equals(record.zoneName, ignoreCase = true) ||
                        record.name.endsWith(".${it.name}", ignoreCase = true) ||
                        record.name.equals(it.name, ignoreCase = true)
                    }
                    if (cached != null && cached.id.isNotBlank()) {
                        targetZoneId = cached.id
                    } else {
                        val queryDomain = if (record.zoneName.isNotBlank()) record.zoneName.trim() else record.name.substringAfter(".")
                        if (queryDomain.isNotBlank()) {
                            try {
                                val zUrl = URL("https://api.cloudflare.com/client/v4/zones?name=$queryDomain")
                                val zConn = (zUrl.openConnection() as HttpURLConnection).apply {
                                    requestMethod = "GET"
                                    setRequestProperty("Authorization", "Bearer $token")
                                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) MaaS-Mobile/1.0")
                                    connectTimeout = 8000
                                    readTimeout = 8000
                                }
                                if (zConn.responseCode in 200..299) {
                                    val zResp = zConn.inputStream.bufferedReader().readText()
                                    val zJson = JSONObject(zResp)
                                    val zResults = zJson.optJSONArray("result")
                                    if (zResults != null && zResults.length() > 0) {
                                        targetZoneId = zResults.getJSONObject(0).optString("id", "")
                                    }
                                }
                                zConn.disconnect()
                            } catch (_: Exception) {}
                        }
                    }
                }

                if (targetZoneId.isBlank() || targetZoneId == "local-zone") {
                    // Save locally if zone could not be resolved from Cloudflare
                    val localRecord = record.copy(
                        id = record.id.ifBlank { "local-${System.currentTimeMillis()}" },
                        zoneId = "local-zone"
                    )
                    withContext(Dispatchers.Main) {
                        val updated = _managedDnsRecords.value.filter { it.name != localRecord.name || it.type != localRecord.type }.toMutableList()
                        updated.add(0, localRecord)
                        persistDnsRecords(updated)
                        onResult(true, localRecord, "Subdomain configured locally. (Domain not active on Cloudflare account)")
                    }
                    return@launch
                }
                // Check if record already exists
                val searchUrl = URL("https://api.cloudflare.com/client/v4/zones/$targetZoneId/dns_records?name=${record.name}&type=${record.type}")
                val searchConn = (searchUrl.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("Authorization", "Bearer $token")
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) MaaS-Mobile/1.0")
                    connectTimeout = 10000
                    readTimeout = 10000
                }
                val searchResp = (if (searchConn.responseCode in 200..299) searchConn.inputStream else searchConn.errorStream)?.bufferedReader()?.readText() ?: ""
                searchConn.disconnect()

                var existingRecordId: String? = null
                try {
                    val sJson = JSONObject(searchResp)
                    val sRes = sJson.optJSONArray("result")
                    if (sRes != null && sRes.length() > 0) {
                        val foundId = sRes.getJSONObject(0).optString("id", "")
                        if (foundId.isNotBlank()) existingRecordId = foundId
                    }
                } catch (_: Exception) {}

                val isUpdate = !existingRecordId.isNullOrBlank()
                val targetUrlStr = if (isUpdate) {
                    "https://api.cloudflare.com/client/v4/zones/$targetZoneId/dns_records/$existingRecordId"
                } else {
                    "https://api.cloudflare.com/client/v4/zones/$targetZoneId/dns_records"
                }

                val payload = JSONObject().apply {
                    put("type", record.type.uppercase())
                    put("name", record.name)
                    put("content", record.content)
                    put("ttl", if (record.ttl <= 0) 1 else record.ttl)
                    if (record.type.uppercase() in listOf("CNAME", "A", "AAAA")) {
                        put("proxied", record.proxied)
                    }
                    put("comment", record.comment.ifBlank { "Managed by MaaS Mobile" })
                }

                val conn = (URL(targetUrlStr).openConnection() as HttpURLConnection).apply {
                    requestMethod = if (isUpdate) "PUT" else "POST"
                    setRequestProperty("Authorization", "Bearer $token")
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) MaaS-Mobile/1.0")
                    doOutput = true
                    connectTimeout = 15000
                    readTimeout = 15000
                }

                conn.outputStream.use { os ->
                    os.write(payload.toString().toByteArray(StandardCharsets.UTF_8))
                }

                val code = conn.responseCode
                val resp = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText() ?: ""
                conn.disconnect()

                val json = JSONObject(resp)
                if (json.optBoolean("success", false)) {
                    val resObj = json.optJSONObject("result")
                    val savedRecord = CloudflareDnsRecord(
                        id = resObj?.optString("id", "") ?: (existingRecordId ?: ""),
                        zoneId = targetZoneId,
                        zoneName = resObj?.optString("zone_name", "") ?: record.zoneName,
                        type = record.type.uppercase(),
                        name = record.name,
                        content = record.content,
                        proxied = if (record.type.uppercase() in listOf("CNAME", "A", "AAAA")) record.proxied else false,
                        ttl = record.ttl,
                        comment = record.comment,
                        targetPort = record.targetPort,
                        serviceName = record.serviceName
                    )
                    withContext(Dispatchers.Main) {
                        val updated = _managedDnsRecords.value.filter { it.id != savedRecord.id && it.name != savedRecord.name }.toMutableList()
                        updated.add(0, savedRecord)
                        persistDnsRecords(updated)
                        onResult(true, savedRecord, if (isUpdate) "Updated ${record.type} record on Cloudflare" else "Created ${record.type} record on Cloudflare!")
                    }
                } else {
                    val errors = json.optJSONArray("errors")
                    val errMsg = if (errors != null && errors.length() > 0) errors.getJSONObject(0).optString("message", "API Error") else "Failed to configure DNS record"
                    withContext(Dispatchers.Main) {
                        onResult(false, null, errMsg)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onResult(false, null, "Error: ${e.message}")
                }
            }
        }
    }

    /**
     * Deletes a DNS record from Cloudflare.
     */
    fun deleteDnsRecord(
        zoneId: String,
        recordId: String,
        onResult: (success: Boolean, message: String) -> Unit
    ) {
        val token = _config.value.apiToken.trim()
        if (token.isBlank() || zoneId.isBlank() || zoneId == "local-zone" || recordId.startsWith("local-")) {
            val updated = _managedDnsRecords.value.filter { it.id != recordId }
            persistDnsRecords(updated)
            onResult(true, "Removed locally")
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                val url = URL("https://api.cloudflare.com/client/v4/zones/$zoneId/dns_records/$recordId")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "DELETE"
                    setRequestProperty("Authorization", "Bearer $token")
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) MaaS-Mobile/1.0")
                    connectTimeout = 12000
                    readTimeout = 12000
                }
                val code = conn.responseCode
                val resp = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText() ?: ""
                conn.disconnect()

                val json = JSONObject(resp)
                if (json.optBoolean("success", false) || code in 200..299) {
                    withContext(Dispatchers.Main) {
                        val updated = _managedDnsRecords.value.filter { it.id != recordId }
                        persistDnsRecords(updated)
                        onResult(true, "DNS record removed from Cloudflare")
                    }
                } else {
                    val errors = json.optJSONArray("errors")
                    val errMsg = if (errors != null && errors.length() > 0) errors.getJSONObject(0).optString("message", "Delete failed") else "Delete failed"
                    withContext(Dispatchers.Main) {
                        onResult(false, errMsg)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onResult(false, "Error: ${e.message}")
                }
            }
        }
    }

    /**
     * Refreshes the zones associated with the current API token.
     */
    fun refreshZones(onResult: (Boolean, List<CloudflareZone>, String) -> Unit) {
        val token = _config.value.apiToken.trim()
        if (token.isBlank()) {
            onResult(false, emptyList(), "No Cloudflare API token configured")
            return
        }
        verifyCloudflareToken(token) { success, msg ->
            if (success) {
                onResult(true, getAvailableZones(), msg)
            } else {
                onResult(false, emptyList(), msg)
            }
        }
    }

    /**
     * Checks if cloudflared binary is available in the DirectTermuxSDK.
     */
    fun hasCloudflaredBinary(directTermuxSDK: DirectTermuxSDK?): Boolean {
        if (directTermuxSDK == null) return false
        return java.io.File(directTermuxSDK.binDir, "cloudflared").exists()
    }

    /**
     * Complete automated 1-click Custom Domain Deployer:
     * 1. Resolves Account ID & Zone.
     * 2. Finds or provisions Named Tunnel (`maas-node`).
     * 3. Retrieves Tunnel Token.
     * 4. Updates Cloudflare Tunnel Ingress configuration for the domain -> localhost port.
     * 5. Creates/updates Cloudflare DNS CNAME record (proxied: true) pointing to the tunnel.
     * 6. Automatically starts the tunnel process on device and updates state.
     */
    fun deployCustomDomainTunnel(
        zone: CloudflareZone,
        subdomain: String,
        targetPort: Int,
        serviceName: String? = null,
        directTermuxSDK: DirectTermuxSDK? = null,
        onProgress: (String) -> Unit = {},
        onComplete: (Boolean, String?, String) -> Unit
    ) {
        val currentCfg = _config.value
        val token = currentCfg.apiToken.trim()
        if (token.isBlank()) {
            onComplete(false, null, "Cloudflare API Token is missing. Please save and verify your API token first.")
            return
        }

        val cleanSub = subdomain.trim().lowercase().removePrefix(".").removeSuffix(".")
        val fullDomain = if (cleanSub.isEmpty() || cleanSub == "@") zone.name else "$cleanSub.${zone.name}"

        scope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) { onProgress("Resolving Cloudflare account...") }

                // 1. Resolve Account ID
                var accountId = currentCfg.accountId.trim()
                if (accountId.isBlank() && zone.id.isNotBlank()) {
                    try {
                        val zConn = (URL("https://api.cloudflare.com/client/v4/zones/${zone.id}").openConnection() as HttpURLConnection).apply {
                            requestMethod = "GET"
                            setRequestProperty("Authorization", "Bearer $token")
                            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) MaaS-Mobile/1.0")
                            connectTimeout = 8000
                            readTimeout = 8000
                        }
                        if (zConn.responseCode in 200..299) {
                            val zResp = zConn.inputStream.bufferedReader().readText()
                            val zJson = JSONObject(zResp)
                            accountId = zJson.optJSONObject("result")?.optJSONObject("account")?.optString("id", "") ?: ""
                        }
                        zConn.disconnect()
                    } catch (_: Exception) {}
                }

                if (accountId.isBlank()) {
                    try {
                        val accConn = (URL("https://api.cloudflare.com/client/v4/accounts").openConnection() as HttpURLConnection).apply {
                            requestMethod = "GET"
                            setRequestProperty("Authorization", "Bearer $token")
                            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) MaaS-Mobile/1.0")
                            connectTimeout = 8000
                            readTimeout = 8000
                        }
                        if (accConn.responseCode in 200..299) {
                            val aResp = accConn.inputStream.bufferedReader().readText()
                            val aJson = JSONObject(aResp)
                            val aArr = aJson.optJSONArray("result")
                            if (aArr != null && aArr.length() > 0) {
                                accountId = aArr.getJSONObject(0).optString("id", "")
                            }
                        }
                        accConn.disconnect()
                    } catch (_: Exception) {}
                }

                if (accountId.isBlank()) {
                    withContext(Dispatchers.Main) {
                        onComplete(false, null, "Could not determine Cloudflare Account ID. Please verify token permissions.")
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) { onProgress("Checking Cloudflare Named Tunnel (maas-node)...") }

                // 2. Query or Create Named Tunnel (maas-node)
                var tunnelId = ""
                try {
                    val tListConn = (URL("https://api.cloudflare.com/client/v4/accounts/$accountId/cfd_tunnel?is_deleted=false&name=maas-node").openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        setRequestProperty("Authorization", "Bearer $token")
                        setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) MaaS-Mobile/1.0")
                        connectTimeout = 10000
                        readTimeout = 10000
                    }
                    if (tListConn.responseCode in 200..299) {
                        val tResp = tListConn.inputStream.bufferedReader().readText()
                        val tArr = JSONObject(tResp).optJSONArray("result")
                        if (tArr != null && tArr.length() > 0) {
                            tunnelId = tArr.getJSONObject(0).optString("id", "")
                        }
                    }
                    tListConn.disconnect()
                } catch (_: Exception) {}

                if (tunnelId.isBlank()) {
                    // Create tunnel
                    val secretBytes = ByteArray(32)
                    SecureRandom().nextBytes(secretBytes)
                    val secretBase64 = Base64.encodeToString(secretBytes, Base64.NO_WRAP)

                    val createPayload = JSONObject().apply {
                        put("name", "maas-node")
                        put("tunnel_secret", secretBase64)
                    }

                    val createConn = (URL("https://api.cloudflare.com/client/v4/accounts/$accountId/cfd_tunnel").openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        setRequestProperty("Authorization", "Bearer $token")
                        setRequestProperty("Content-Type", "application/json")
                        setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) MaaS-Mobile/1.0")
                        doOutput = true
                        connectTimeout = 12000
                        readTimeout = 12000
                    }
                    createConn.outputStream.use { it.write(createPayload.toString().toByteArray(StandardCharsets.UTF_8)) }
                    if (createConn.responseCode in 200..299) {
                        val cResp = createConn.inputStream.bufferedReader().readText()
                        tunnelId = JSONObject(cResp).optJSONObject("result")?.optString("id", "") ?: ""
                    }
                    createConn.disconnect()
                }

                if (tunnelId.isBlank()) {
                    withContext(Dispatchers.Main) {
                        onComplete(false, null, "Failed to create or find Cloudflare Named Tunnel.")
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) { onProgress("Retrieving Tunnel authorization token...") }

                // 3. Fetch Tunnel Token
                var tunnelToken = ""
                try {
                    val tokConn = (URL("https://api.cloudflare.com/client/v4/accounts/$accountId/cfd_tunnel/$tunnelId/token").openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        setRequestProperty("Authorization", "Bearer $token")
                        setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) MaaS-Mobile/1.0")
                        connectTimeout = 10000
                        readTimeout = 10000
                    }
                    if (tokConn.responseCode in 200..299) {
                        val tokResp = tokConn.inputStream.bufferedReader().readText()
                        tunnelToken = JSONObject(tokResp).optString("result", "")
                    }
                    tokConn.disconnect()
                } catch (_: Exception) {}

                if (tunnelToken.isBlank()) {
                    tunnelToken = currentCfg.tunnelToken
                }

                if (tunnelToken.isBlank()) {
                    withContext(Dispatchers.Main) {
                        onComplete(false, null, "Failed to retrieve Cloudflare Tunnel Token for tunnel $tunnelId.")
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) { onProgress("Routing $fullDomain to local port :$targetPort...") }

                // 4. Update Tunnel Ingress Configurations
                try {
                    val cfgGetConn = (URL("https://api.cloudflare.com/client/v4/accounts/$accountId/cfd_tunnel/$tunnelId/configurations").openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        setRequestProperty("Authorization", "Bearer $token")
                        setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) MaaS-Mobile/1.0")
                        connectTimeout = 10000
                        readTimeout = 10000
                    }
                    val existingRules = mutableListOf<JSONObject>()
                    if (cfgGetConn.responseCode in 200..299) {
                        val cfgResp = cfgGetConn.inputStream.bufferedReader().readText()
                        val ingArr = JSONObject(cfgResp).optJSONObject("result")?.optJSONObject("config")?.optJSONArray("ingress")
                        if (ingArr != null) {
                            for (i in 0 until ingArr.length()) {
                                val item = ingArr.getJSONObject(i)
                                val host = item.optString("hostname", "")
                                val svc = item.optString("service", "")
                                if (host.isNotBlank() && !host.equals(fullDomain, ignoreCase = true) && !host.contains("aapanel", ignoreCase = true) && !svc.contains("41860")) {
                                    existingRules.add(item)
                                }
                            }
                        }
                    }
                    cfgGetConn.disconnect()

                    val newRule = JSONObject().apply {
                        put("hostname", fullDomain)
                        put("service", "http://127.0.0.1:$targetPort")
                    }
                    val allRules = org.json.JSONArray().apply {
                        put(newRule)
                        existingRules.forEach { put(it) }
                        put(JSONObject().apply { put("service", "http_status:404") })
                    }

                    val putCfgPayload = JSONObject().apply {
                        put("config", JSONObject().apply {
                            put("ingress", allRules)
                        })
                    }

                    val cfgPutConn = (URL("https://api.cloudflare.com/client/v4/accounts/$accountId/cfd_tunnel/$tunnelId/configurations").openConnection() as HttpURLConnection).apply {
                        requestMethod = "PUT"
                        setRequestProperty("Authorization", "Bearer $token")
                        setRequestProperty("Content-Type", "application/json")
                        setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) MaaS-Mobile/1.0")
                        doOutput = true
                        connectTimeout = 12000
                        readTimeout = 12000
                    }
                    cfgPutConn.outputStream.use { it.write(putCfgPayload.toString().toByteArray(StandardCharsets.UTF_8)) }
                    cfgPutConn.responseCode
                    cfgPutConn.disconnect()
                } catch (_: Exception) {}

                withContext(Dispatchers.Main) { onProgress("Configuring Cloudflare DNS (CNAME -> $fullDomain)...") }

                // 5. Create or Update DNS CNAME Record
                val dnsRecord = CloudflareDnsRecord(
                    zoneId = zone.id,
                    zoneName = zone.name,
                    type = "CNAME",
                    name = fullDomain,
                    content = "$tunnelId.cfargotunnel.com",
                    proxied = true,
                    ttl = 1,
                    comment = "MaaS Mobile Tunnel (${serviceName ?: ":$targetPort"})",
                    targetPort = targetPort,
                    serviceName = serviceName ?: fullDomain
                )

                var dnsSuccess = false
                var dnsErrorMsg = ""

                val targetZoneId = zone.id.ifBlank {
                    var zid = ""
                    try {
                        val zConn = (URL("https://api.cloudflare.com/client/v4/zones?name=${zone.name}").openConnection() as HttpURLConnection).apply {
                            requestMethod = "GET"
                            setRequestProperty("Authorization", "Bearer $token")
                            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) MaaS-Mobile/1.0")
                        }
                        if (zConn.responseCode in 200..299) {
                            val zJson = JSONObject(zConn.inputStream.bufferedReader().readText())
                            val r = zJson.optJSONArray("result")
                            if (r != null && r.length() > 0) zid = r.getJSONObject(0).optString("id", "")
                        }
                        zConn.disconnect()
                    } catch (_: Exception) {}
                    zid
                }

                if (targetZoneId.isNotBlank()) {
                    var existingRecId: String? = null
                    try {
                        val sConn = (URL("https://api.cloudflare.com/client/v4/zones/$targetZoneId/dns_records?name=$fullDomain&type=CNAME").openConnection() as HttpURLConnection).apply {
                            requestMethod = "GET"
                            setRequestProperty("Authorization", "Bearer $token")
                            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) MaaS-Mobile/1.0")
                        }
                        if (sConn.responseCode in 200..299) {
                            val sRes = JSONObject(sConn.inputStream.bufferedReader().readText()).optJSONArray("result")
                            if (sRes != null && sRes.length() > 0) existingRecId = sRes.getJSONObject(0).optString("id", "")
                        }
                        sConn.disconnect()
                    } catch (_: Exception) {}

                    val dnsUrl = if (existingRecId != null) {
                        "https://api.cloudflare.com/client/v4/zones/$targetZoneId/dns_records/$existingRecId"
                    } else {
                        "https://api.cloudflare.com/client/v4/zones/$targetZoneId/dns_records"
                    }

                    val dnsPayload = JSONObject().apply {
                        put("type", "CNAME")
                        put("name", fullDomain)
                        put("content", "$tunnelId.cfargotunnel.com")
                        put("proxied", true)
                        put("ttl", 1)
                        put("comment", "MaaS Mobile Node Tunnel")
                    }

                    val dnsConn = (URL(dnsUrl).openConnection() as HttpURLConnection).apply {
                        requestMethod = if (existingRecId != null) "PUT" else "POST"
                        setRequestProperty("Authorization", "Bearer $token")
                        setRequestProperty("Content-Type", "application/json")
                        setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) MaaS-Mobile/1.0")
                        doOutput = true
                        connectTimeout = 12000
                        readTimeout = 12000
                    }
                    dnsConn.outputStream.use { it.write(dnsPayload.toString().toByteArray(StandardCharsets.UTF_8)) }
                    val dCode = dnsConn.responseCode
                    val dResp = (if (dCode in 200..299) dnsConn.inputStream else dnsConn.errorStream)?.bufferedReader()?.readText() ?: ""
                    dnsConn.disconnect()

                    val dJson = JSONObject(dResp)
                    if (dJson.optBoolean("success", false)) {
                        dnsSuccess = true
                        val resObj = dJson.optJSONObject("result")
                        val savedRec = dnsRecord.copy(id = resObj?.optString("id", "") ?: (existingRecId ?: ""))
                        val updatedList = _managedDnsRecords.value.filter { it.name != fullDomain }.toMutableList()
                        updatedList.add(0, savedRec)
                        persistDnsRecords(updatedList)
                    } else {
                        val errs = dJson.optJSONArray("errors")
                        dnsErrorMsg = if (errs != null && errs.length() > 0) errs.getJSONObject(0).optString("message", "DNS configuration error") else "DNS failed"
                    }
                } else {
                    dnsErrorMsg = "Zone ID could not be resolved for domain ${zone.name}"
                }

                if (!dnsSuccess) {
                    withContext(Dispatchers.Main) {
                        onComplete(false, null, "Cloudflare DNS Error: $dnsErrorMsg")
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) { onProgress("Starting Cloudflare Tunnel on device...") }

                // 6. Update Config and Start Tunnel
                val updatedCfg = currentCfg.copy(
                    mode = TunnelMode.CUSTOM_DOMAIN,
                    apiToken = token,
                    accountId = accountId,
                    tunnelToken = tunnelToken,
                    customDomain = fullDomain
                )
                withContext(Dispatchers.Main) {
                    updateConfig(updatedCfg)
                }

                startTunnelInternal(
                    targetPort = targetPort,
                    serviceName = serviceName ?: fullDomain,
                    tunnelId = "custom_${targetPort}_${System.currentTimeMillis()}",
                    directTermuxSDK = directTermuxSDK,
                    isCustom = true,
                    customDomain = fullDomain,
                    tunnelToken = tunnelToken,
                    logCallback = {}
                )

                val liveUrl = "https://$fullDomain"
                withContext(Dispatchers.Main) {
                    onComplete(true, liveUrl, "Congratulations! Deployed successfully to $liveUrl")
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onComplete(false, null, "Deployment error: ${e.message}")
                }
            }
        }
    }
}

