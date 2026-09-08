package com.buildmain.services

import com.buildmain.models.jeko.JekoPaymentLinkRequest
import com.buildmain.models.jeko.JekoPaymentLinkResponse
import com.buildmain.models.jeko.JekoPaymentRequest
import com.buildmain.models.jeko.JekoPaymentResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Service d'intégration avec l'API Partenaire Jèko (Encaissement Mobile Money & Cartes).
 * Utilise Ktor HttpClient avec le moteur CIO haute performance.
 */
class JekoPaymentService(
    private val baseUrl: String = com.buildmain.config.EnvConfig.get("JEKO_API_BASE_URL", "https://api.jeko.africa") ?: "https://api.jeko.africa"
) {
    private val logger = LoggerFactory.getLogger(JekoPaymentService::class.java)

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
                isLenient = true
            })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 45_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 45_000
        }
    }

    /**
     * Initialise une session de paiement (Pay-in direct / Hosted Checkout) via Jèko.
     * Si les clés de test sont utilisées (préfixe test_), génère une session simulée valide pour les tests.
     */
    suspend fun initiatePayIn(
        apiKey: String,
        apiKeyId: String,
        request: JekoPaymentRequest
    ): JekoPaymentResponse {
        // En mode test/développement local avec des identifiants démo
        if (apiKey.startsWith("test_") || apiKeyId.startsWith("test_")) {
            logger.info("Mode test actif pour Jèko: génération d'une session Pay-in simulée pour ref=${request.reference}")
            return JekoPaymentResponse(
                id = "req_${java.util.UUID.randomUUID()}",
                storeId = request.storeId,
                reference = request.reference,
                type = request.paymentDetails.type,
                paymentMethod = request.paymentDetails.data.paymentMethod,
                status = com.buildmain.models.jeko.JekoPaymentStatus.PENDING,
                redirectUrl = "https://pay.jeko.africa/checkout/simulated-${request.reference}"
            )
        }

        val endpoint = "$baseUrl/partner_api/payment_requests"
        val cleanApiKey = apiKey.trim()
        val cleanApiKeyId = apiKeyId.trim()

        MonitoringService.info(
            category = "JEKO_API",
            message = "Envoi Pay-in vers Jèko pour ref=${request.reference}",
            details = "Montant=${request.amountCents / 100} FCFA, Méthode=${request.paymentDetails.data.paymentMethod}, KeyId=$cleanApiKeyId"
        )

        val response = try {
            client.post(endpoint) {
                contentType(ContentType.Application.Json)
                header("X-API-KEY", cleanApiKey)
                header("X-API-KEY-ID", cleanApiKeyId)
                header("Accept", "application/json")
                setBody(request)
            }
        } catch (e: Exception) {
            MonitoringService.error(
                category = "JEKO_API",
                message = "Erreur de connexion réseau vers Jèko API ($endpoint)",
                details = e.message
            )
            throw e
        }

        if (!response.status.isSuccess()) {
            val errorBody = response.bodyAsText()
            MonitoringService.error(
                category = "JEKO_API",
                message = "Échec API Jèko (HTTP ${response.status.value}) pour ref=${request.reference}",
                details = errorBody
            )
            throw JekoApiException(
                statusCode = response.status.value,
                message = "Erreur Jèko (${response.status.value}): $errorBody"
            )
        }

        val result = response.body<JekoPaymentResponse>()
        MonitoringService.success(
            category = "JEKO_API",
            message = "Session Jèko créée avec succès pour ref=${request.reference}",
            details = "ID=${result.id}, Statut=${result.status}, Redirect=${result.redirectUrl}"
        )
        return result
    }

    /**
     * Teste la connectivité et la validité des clés avec l'API Jèko (GET /partner_api/stores).
     */
    suspend fun pingStores(apiKey: String, apiKeyId: String): Pair<Boolean, String> {
        val endpoint = "$baseUrl/partner_api/stores"
        val cleanApiKey = apiKey.trim()
        val cleanApiKeyId = apiKeyId.trim()
        return try {
            val start = System.currentTimeMillis()
            val response = client.get(endpoint) {
                header("X-API-KEY", cleanApiKey)
                header("X-API-KEY-ID", cleanApiKeyId)
                header("Accept", "application/json")
            }
            val duration = System.currentTimeMillis() - start
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                MonitoringService.success(
                    category = "JEKO_API",
                    message = "Ping API Jèko réussi (${duration}ms)",
                    details = body
                )
                Pair(true, "Connexion réussie (${duration}ms) : $body")
            } else {
                MonitoringService.error(
                    category = "JEKO_API",
                    message = "Ping API Jèko échoué (HTTP ${response.status.value})",
                    details = body
                )
                Pair(false, "HTTP ${response.status.value}: $body")
            }
        } catch (e: Exception) {
            MonitoringService.error(
                category = "JEKO_API",
                message = "Exception lors du ping Jèko",
                details = e.message
            )
            Pair(false, "Exception: ${e.message}")
        }
    }

    /**
     * Crée un lien de paiement Jèko partageable (ex: campagne de moisson ou dons généraux).
     */
    suspend fun createPaymentLink(
        apiKey: String,
        apiKeyId: String,
        request: JekoPaymentLinkRequest
    ): JekoPaymentLinkResponse {
        val endpoint = "$baseUrl/partner_api/payment_links"
        logger.info("Appel Jèko Payment Link vers $endpoint pour titre='${request.title}'")

        val response = client.post(endpoint) {
            contentType(ContentType.Application.Json)
            header("X-API-KEY", apiKey)
            header("X-API-KEY-ID", apiKeyId)
            header("Accept", "application/json")
            setBody(request)
        }

        if (!response.status.isSuccess()) {
            val errorBody = response.bodyAsText()
            logger.error("Échec création lien de paiement Jèko (${response.status.value}): $errorBody")
            throw JekoApiException(
                statusCode = response.status.value,
                message = "Erreur Jèko Payment Link (${response.status.value}): $errorBody"
            )
        }

        return response.body<JekoPaymentLinkResponse>()
    }

    fun close() {
        client.close()
    }
}

class JekoApiException(val statusCode: Int, override val message: String) : RuntimeException(message)
