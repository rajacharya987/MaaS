package com.wiperspace.maas.model

enum class ServiceType(val displayName: String, val iconName: String)
{
    STATIC_WEB("Static Website", "web"),
    PHP_APP("PHP / Web App", "language"),
    NODE_API("Node.js / REST API", "code"),
    PYTHON_WORKER("SQLite Database", "storage"),
    MARIADB_SQL("Redis KV Store", "cached"),
    CUSTOM("Custom Microservice", "settings_ethernet")
}

enum class ServiceStatus {
    ONLINE,
    STOPPED,
    STARTING,
    ERROR
}

data class ServiceMetrics(
    val requestsCount : Long = 0,
    val uptimeSeconds : Long = 0,
    val memoryUsageMb : Float = 12.4f,
    val avgLatencyMs : Int = 8
)

data class ServiceItem (
    val id : String,
    val name : String,
    val description : String = "",
    val type : ServiceType,
    val status : ServiceStatus = ServiceStatus.STOPPED,
    val port : Int,
    val domain : String? = null,
    val DirectoryPath : String = "",
    val autoRestart: Boolean = true,
    val CreatedAt : Long = System.currentTimeMillis(),
    val Metrics: ServiceMetrics = ServiceMetrics(),
    val envVars : Map<String, String> = emptyMap(),
    val customRoutes : List<HttpRouteMock> = emptyList()
)

data class HttpRouteMock(
    val path :String = "/",
    val method : String = "GET",
    val statusCode : Int = 200,
    val responseBody : String = "{\"status\":\"ok\"}",
    val contentType : String = "application/json"

)