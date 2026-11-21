package com.wiperspace.maas.model

import android.R
import androidx.compose.runtime.FloatState

data class SystemMetrics(
    val cpuLoadPercent: Int = 14,
    val cpuCores: Int = 8,
    val ramUsedBytes : Long = 3_45_973_836L, //3.2 GB
    val ramTotalBytes : Long = 8_589_934_592L, // 8GB
    val storageUsedBytes : Long = 85_899_345_920L, //80 gb
    val storageTotalBytes: Long = 274_877_906_944L, // 265gb
    val sdCardUsedBytes : Long = 0L,
    val sdCardTotalBytes : Long = 0L,
    val hasSdCard: Boolean = false,
    val networkRxSpeedBytes : Long = 245_760L,
    val networkTxSpeedBytes : Long = 81_920L,
    val batteryPercent: Int = 85,
    val batteryTemperature: Float = 32.5f,
    val ischarging: Boolean = true,
    val uptimeSeconds : Long = 0L,
    val localIpAddress : String = "127.0.0.1",
    val tunnelConnected : Boolean = false,
    val tunnelPublicUrl: String? = null,
    val activeServicesCount: Int = 0,
    val totalRequestsServed: Long = 0L
) {
    val ramUsedGb : Float
        get() = ramUsedBytes / (1024f * 1024f * 1024f)
    val ramTotalGb : Float
        get() = ramTotalBytes / (1024f * 1024f * 1024f)
    val ramPercent : Int
        get() = if (ramTotalBytes > 0) ((ramUsedBytes * 100) / ramTotalBytes).toInt() else 0
    val storageUsedGb: Float
        get() = storageUsedBytes / (1024f * 1024f * 1024f)
    val storageTotalGb : Float
        get() = storageTotalBytes /(1024f * 1024f * 1024f)
    val storagePercent : Int
        get()=if (storageTotalBytes > 0) ((storageUsedBytes *100)/storageTotalBytes).toInt() else 0
    fun formatSpeed(bytesPerSec: Long): String {
        return when {
            bytesPerSec >= 1024*1024 -> String.format(java.util.Locale.US,"%.1f MB/s", bytesPerSec / (1024f * 1024f) )
            bytesPerSec >= 1024 -> String.format(java.util.Locale.US,"%.0f KB/s", bytesPerSec / 1024f)
            else -> "$bytesPerSec B/s"

        }
    }
}