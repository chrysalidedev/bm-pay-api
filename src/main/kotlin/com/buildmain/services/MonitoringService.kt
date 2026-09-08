package com.buildmain.services

import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.send
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

@Serializable
data class LogEvent(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: String,
    val level: String,      // "SUCCESS", "INFO", "WARN", "ERROR"
    val category: String,   // "JEKO_API", "WEBHOOK", "DONATION", "DATABASE", "SYSTEM"
    val message: String,
    val details: String? = null
)

@Serializable
data class SystemHealth(
    val status: String, // "HEALTHY", "DEGRADED", "ERROR"
    val database: String,
    val jekoApi: String,
    val jekoStoreId: String,
    val hasWebhookSecret: Boolean,
    val liveSubscribers: Int,
    val monitoringSubscribers: Int,
    val uptimeSeconds: Long,
    val timestamp: String
)

object MonitoringService {
    private val logger = LoggerFactory.getLogger("MonitoringService")
    private val startTime = System.currentTimeMillis()

    // Tampon glissant des 150 derniers événements
    private val maxLogs = 150
    private val logBuffer = ConcurrentLinkedDeque<LogEvent>()

    // Sessions WebSocket abonnées aux logs en direct
    private val subscribers = Collections.synchronizedSet(LinkedHashSet<DefaultWebSocketSession>())

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun registerSubscriber(session: DefaultWebSocketSession) {
        subscribers.add(session)
        logger.info("Nouveau client de monitoring connecté (Total: ${subscribers.size})")
    }

    fun unregisterSubscriber(session: DefaultWebSocketSession) {
        subscribers.remove(session)
        logger.info("Déconnexion client de monitoring (Restants: ${subscribers.size})")
    }

    suspend fun log(
        level: String,
        category: String,
        message: String,
        details: String? = null
    ) {
        val now = Clock.System.now().toString()
        val event = LogEvent(
            id = UUID.randomUUID().toString(),
            timestamp = now,
            level = level,
            category = category,
            message = message,
            details = details
        )

        // Ajout dans le tampon mémoire
        logBuffer.addFirst(event)
        while (logBuffer.size > maxLogs) {
            logBuffer.pollLast()
        }

        // Affichage console formaté
        val badge = when (level) {
            "SUCCESS" -> "🟢 [SUCCESS]"
            "ERROR"   -> "🔴 [ERROR]  "
            "WARN"    -> "🟡 [WARN]   "
            else      -> "🔵 [INFO]   "
        }
        val logLine = "$badge [$category] $message ${details?.let { "--> $it" } ?: ""}"
        when (level) {
            "ERROR" -> logger.error(logLine)
            "WARN"  -> logger.warn(logLine)
            else    -> logger.info(logLine)
        }

        // Diffusion WebSocket temps réel aux dashboards ouverts
        if (subscribers.isNotEmpty()) {
            val messagePayload = json.encodeToString(LogEvent.serializer(), event)
            val deadSessions = mutableListOf<DefaultWebSocketSession>()
            subscribers.forEach { session ->
                try {
                    session.send(messagePayload)
                } catch (e: Exception) {
                    deadSessions.add(session)
                }
            }
            if (deadSessions.isNotEmpty()) {
                subscribers.removeAll(deadSessions.toSet())
            }
        }
    }

    suspend fun success(category: String, message: String, details: String? = null) =
        log("SUCCESS", category, message, details)

    suspend fun info(category: String, message: String, details: String? = null) =
        log("INFO", category, message, details)

    suspend fun warn(category: String, message: String, details: String? = null) =
        log("WARN", category, message, details)

    suspend fun error(category: String, message: String, details: String? = null) =
        log("ERROR", category, message, details)

    fun getRecentLogs(limit: Int = 100): List<LogEvent> {
        return logBuffer.take(limit)
    }

    fun getHealth(databaseConnected: Boolean, storeId: String, hasWebhookSecret: Boolean, liveSubscribersCount: Int): SystemHealth {
        val uptime = (System.currentTimeMillis() - startTime) / 1000
        val isHealthy = databaseConnected && storeId.isNotBlank() && hasWebhookSecret

        return SystemHealth(
            status = if (isHealthy) "HEALTHY" else "DEGRADED",
            database = if (databaseConnected) "CONNECTED (SQLite)" else "DISCONNECTED",
            jekoApi = "https://api.jeko.africa",
            jekoStoreId = storeId,
            hasWebhookSecret = hasWebhookSecret,
            liveSubscribers = liveSubscribersCount,
            monitoringSubscribers = subscribers.size,
            uptimeSeconds = uptime,
            timestamp = Clock.System.now().toString()
        )
    }
}
