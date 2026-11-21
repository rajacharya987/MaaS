package com.wiperspace.maas.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LogLevel {
    INFO,
    SUCCESS,
    WARN,
    ERROR,
    SYSTEM
}

data class LogEntry(
    val id: String = java.util.UUID.randomUUID().toString(),
    val serviceID: String? = null,
    val serviceName: String? = null,
    val level: LogLevel = LogLevel.INFO,
    val message: String,
    val timestamp: Long = System.currentTimeMillis()

)
{
    val formattedTime : String
        get() = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(timestamp))
}