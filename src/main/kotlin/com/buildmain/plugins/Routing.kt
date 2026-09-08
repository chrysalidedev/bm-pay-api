package com.buildmain.plugins

import com.buildmain.config.DatabaseFactory
import com.buildmain.routes.donationRoutes
import com.buildmain.routes.webhookRoutes
import com.buildmain.services.JekoPaymentService
import com.buildmain.config.EnvConfig
import com.buildmain.models.CreateChurchRequest
import com.buildmain.models.CreateCampaignRequest
import com.buildmain.models.UpdateCampaignRequest
import com.buildmain.models.LoginRequest
import com.buildmain.models.LoginResponse
import com.buildmain.utils.SecurityUtils
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

@Serializable
data class HealthResponse(
    val status: String,
    val service: String,
    val version: String,
    val timestamp: Long
)

fun Application.configureRouting(jekoPaymentService: JekoPaymentService) {
    routing {
        // Endpoint de vérification de santé
        get("/health") {
            call.respond(
                HttpStatusCode.OK,
                HealthResponse(
                    status = "UP",
                    service = "build-main-backend",
                    version = "1.0.0",
                    timestamp = System.currentTimeMillis()
                )
            )
        }

        // Récupération des informations d'une église par ID
        get("/api/churches/{churchId}") {
            val churchId = call.parameters["churchId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val church = DatabaseFactory.findChurchById(churchId)
            if (church != null) {
                // Masquage des secrets sensibles
                call.respond(church.copy(jekoApiKey = "***", jekoWebhookSecret = "***"))
            } else {
                call.respond(HttpStatusCode.NotFound)
            }
        }

        // Récupération d'une église par slug
        get("/api/churches/by-slug/{slug}") {
            val slug = call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val church = DatabaseFactory.findChurchBySlug(slug)
            if (church != null) {
                call.respond(church.copy(jekoApiKey = "***", jekoWebhookSecret = "***"))
            } else {
                call.respond(HttpStatusCode.NotFound)
            }
        }

        // Inscription / Création d'une nouvelle église ou organisation (Onboarding Multi-Tenant)
        post("/api/churches") {
            try {
                val req = call.receive<CreateChurchRequest>()
                if (req.name.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Le nom de l'organisation est obligatoire."))
                    return@post
                }
                val slugClean = req.slug.trim().lowercase().replace("\\s+".toRegex(), "-").replace("[^a-z0-9-]".toRegex(), "")
                if (slugClean.length < 3) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "L'identifiant (slug) doit comporter au moins 3 caractères alphanumériques."))
                    return@post
                }
                if (!req.password.isNullOrBlank() && req.password.length < 6) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Le mot de passe administrateur doit comporter au moins 6 caractères."))
                    return@post
                }

                val church = DatabaseFactory.createChurch(
                    name = req.name.trim(),
                    slug = slugClean,
                    city = req.city.ifBlank { "Abidjan" },
                    adminEmail = req.adminEmail?.trim(),
                    password = req.password,
                    jekoStoreId = req.jekoStoreId,
                    jekoApiKey = req.jekoApiKey,
                    jekoApiKeyId = req.jekoApiKeyId,
                    jekoWebhookSecret = req.jekoWebhookSecret
                )
                com.buildmain.services.MonitoringService.success(
                    category = "SYSTEM",
                    message = "Nouvelle organisation créée: ${church.name} (${church.slug})",
                    details = "ID=${church.id}, Ville=${church.city}, Admin=${church.adminEmail ?: "Non renseigné"}"
                )

                // Génération immédiate du token d'accès administrateur
                val secret = EnvConfig.get("JWT_SECRET", "buildmain_super_secret_jwt_key_2026") ?: "buildmain_super_secret_jwt_key_2026"
                val token = SecurityUtils.generateAuthToken(church.id, church.adminEmail ?: church.slug, secret)
                val sanitizedChurch = church.copy(jekoApiKey = "***", jekoWebhookSecret = "***")

                call.respond(HttpStatusCode.Created, LoginResponse(
                    token = token,
                    church = sanitizedChurch,
                    expiresIn = 7 * 24 * 3600L
                ))
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.Conflict, mapOf("error" to (e.message ?: "Conflit")))
            } catch (e: Exception) {
                com.buildmain.services.MonitoringService.error(
                    category = "SYSTEM",
                    message = "Erreur création organisation",
                    details = e.message
                )
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Erreur interne")))
            }
        }

        // ====================================================================
        // Authentification Organisation & Dashboard
        // ====================================================================

        // Connexion administrateur (via email ou identifiant slug + mot de passe)
        post("/api/auth/login") {
            try {
                val req = call.receive<LoginRequest>()
                if (req.login.isBlank() || req.password.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Veuillez saisir votre identifiant (email ou slug) et mot de passe."))
                    return@post
                }

                val authResult = DatabaseFactory.authenticateChurch(req.login, req.password)
                if (authResult == null) {
                    com.buildmain.services.MonitoringService.warn(
                        category = "AUTH",
                        message = "Échec d'authentification pour '${req.login}'",
                        details = "Identifiant introuvable ou mot de passe incorrect"
                    )
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Identifiant ou mot de passe incorrect."))
                    return@post
                }

                val (church, token) = authResult
                com.buildmain.services.MonitoringService.success(
                    category = "AUTH",
                    message = "Connexion réussie pour ${church.name} (${church.slug})",
                    details = "Admin: ${church.adminEmail ?: req.login}"
                )

                call.respond(HttpStatusCode.OK, LoginResponse(
                    token = token,
                    church = church.copy(jekoApiKey = "***", jekoWebhookSecret = "***"),
                    expiresIn = 7 * 24 * 3600L
                ))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Erreur d'authentification")))
            }
        }

        // Vérification de la session active
        get("/api/auth/me") {
            val authHeader = call.request.headers["Authorization"]
            val church = DatabaseFactory.verifyAuthHeader(authHeader)
            if (church == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Session expirée ou non autorisée."))
                return@get
            }
            call.respond(HttpStatusCode.OK, church.copy(jekoApiKey = "***", jekoWebhookSecret = "***"))
        }

        // Campagnes d'une église
        get("/api/churches/{churchId}/campaigns") {
            val churchId = call.parameters["churchId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val campaigns = DatabaseFactory.findCampaignsByChurch(churchId)
            call.respond(campaigns)
        }

        // Création d'une nouvelle campagne pour une église
        post("/api/churches/{churchId}/campaigns") {
            val churchId = call.parameters["churchId"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val church = DatabaseFactory.findChurchById(churchId)
            if (church == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Organisation introuvable"))
                return@post
            }

            try {
                val req = call.receive<CreateCampaignRequest>()
                if (req.title.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Le titre de la campagne est obligatoire."))
                    return@post
                }

                val campaign = DatabaseFactory.createCampaign(
                    churchId = churchId,
                    title = req.title,
                    type = req.type,
                    description = req.description,
                    targetAmount = req.targetAmount
                )
                com.buildmain.services.MonitoringService.info(
                    category = "DATABASE",
                    message = "Nouvelle campagne créée pour ${church.name}: ${campaign.title}",
                    details = "Type=${campaign.type}, Target=${campaign.targetAmount ?: 0} FCFA"
                )
                call.respond(HttpStatusCode.Created, campaign)
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Erreur lors de la création de la campagne")))
            }
        }

        // Mise à jour d'une campagne
        put("/api/campaigns/{campaignId}") {
            val campaignId = call.parameters["campaignId"] ?: return@put call.respond(HttpStatusCode.BadRequest)
            try {
                val req = call.receive<UpdateCampaignRequest>()
                val updated = DatabaseFactory.updateCampaign(
                    campaignId = campaignId,
                    title = req.title,
                    type = req.type,
                    description = req.description,
                    targetAmount = req.targetAmount,
                    isActive = req.isActive
                )
                if (updated != null) {
                    call.respond(updated)
                } else {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Campagne introuvable"))
                }
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Erreur de mise à jour")))
            }
        }

        // Activation / Désactivation rapide d'une campagne
        post("/api/campaigns/{campaignId}/toggle") {
            val campaignId = call.parameters["campaignId"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val updated = DatabaseFactory.toggleCampaignStatus(campaignId)
            if (updated != null) {
                call.respond(updated)
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Campagne introuvable"))
            }
        }

        // Suppression d'une campagne
        delete("/api/campaigns/{campaignId}") {
            val campaignId = call.parameters["campaignId"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
            val deleted = DatabaseFactory.deleteCampaign(campaignId)
            if (deleted) {
                call.respond(HttpStatusCode.OK, mapOf("success" to true))
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Campagne introuvable"))
            }
        }

        // Dons récents d'une église
        get("/api/churches/{churchId}/donations") {
            val churchId = call.parameters["churchId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
            val donations = DatabaseFactory.findDonationsByChurch(churchId, limit)
            call.respond(donations)
        }

        // Statistiques d'une église (pour dashboard et écran de culte)
        get("/api/churches/{churchId}/stats") {
            val churchId = call.parameters["churchId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val stats = DatabaseFactory.getChurchStats(churchId)
            call.respond(stats)
        }

        // Consultation du statut d'un don par référence
        get("/api/donations/{reference}") {
            val ref = call.parameters["reference"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val donation = DatabaseFactory.findDonationByReference(ref)
            if (donation != null) {
                call.respond(donation)
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Donation not found"))
            }
        }

        // Routes Dons et Paiements Jèko
        donationRoutes(jekoPaymentService)

        // Routes Webhooks
        webhookRoutes()

        // ====================================================================
        // Endpoints de Monitoring & Diagnostic en Temps Réel
        // ====================================================================

        // Récupération des logs récents
        get("/api/monitoring/logs") {
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 100
            call.respond(com.buildmain.services.MonitoringService.getRecentLogs(limit))
        }

        // Bilan de santé complet du système
        get("/api/monitoring/health") {
            val church = DatabaseFactory.findChurchById("chu_abidjan_01")
            val dbOk = church != null
            val storeId = church?.jekoStoreId ?: ""
            val hasWebhookSecret = !church?.jekoWebhookSecret.isNullOrBlank()

            val health = com.buildmain.services.MonitoringService.getHealth(
                databaseConnected = dbOk,
                storeId = storeId,
                hasWebhookSecret = hasWebhookSecret,
                liveSubscribersCount = 0
            )
            call.respond(health)
        }

        // Test direct de l'API Jèko (Ping Stores)
        post("/api/monitoring/test-jeko") {
            val church = DatabaseFactory.findChurchById("chu_abidjan_01")
            if (church == null) {
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Paroisse introuvable"))
                return@post
            }
            val (success, message) = jekoPaymentService.pingStores(church.jekoApiKey, church.jekoApiKeyId)
            call.respond(mapOf("success" to success, "message" to message))
        }
    }
}
