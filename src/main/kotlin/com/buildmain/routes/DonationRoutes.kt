package com.buildmain.routes

import com.buildmain.config.DatabaseFactory
import com.buildmain.models.jeko.JekoPaymentData
import com.buildmain.models.jeko.JekoPaymentDetails
import com.buildmain.models.jeko.JekoPaymentMethod
import com.buildmain.models.jeko.JekoPaymentRequest
import com.buildmain.models.jeko.JekoPaymentType
import com.buildmain.services.JekoPaymentService
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.util.UUID

@Serializable
data class InitiateDonationRequest(
    val churchId: String,
    val campaignId: String,
    val amount: Long, // Montant en FCFA (XOF), minimum 100 FCFA
    val paymentMethod: JekoPaymentMethod,
    val donorName: String? = null,
    val donorPhone: String? = null,
    val successUrl: String,
    val errorUrl: String
)

@Serializable
data class InitiateDonationResponse(
    val donationId: String,
    val reference: String,
    val status: String,
    val amount: Long,
    val currency: String = "XOF",
    val redirectUrl: String
)

@Serializable
data class ApiErrorResponse(
    val error: String,
    val code: String? = null
)

fun Route.donationRoutes(jekoPaymentService: JekoPaymentService) {
    val logger = LoggerFactory.getLogger("DonationRoutes")

    route("/api/donations") {
        /**
         * POST /api/donations/initiate
         * 1. Enregistre le don en base de données avec le statut PENDING.
         * 2. Appelle l'API Partenaire Jèko avec les clés de l'église.
         * 3. Met à jour le don avec la redirectUrl et la renvoie au client/frontend.
         */
        post("/initiate") {
            val req = try {
                call.receive<InitiateDonationRequest>()
            } catch (e: Exception) {
                com.buildmain.services.MonitoringService.warn("DONATION", "Corps de requête invalide", e.message)
                call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("Corps de requête invalide: ${e.message}"))
                return@post
            }

            if (req.amount < 100L) {
                com.buildmain.services.MonitoringService.warn("DONATION", "Montant insuffisant (${req.amount} FCFA < 100 FCFA minimum Jèko)")
                call.respond(
                    HttpStatusCode.BadRequest,
                    ApiErrorResponse("Le montant minimum est de 100 FCFA (10 000 centimes Jèko)")
                )
                return@post
            }

            // 1. Récupération des informations de la paroisse (multi-tenant)
            val church = DatabaseFactory.findChurchById(req.churchId)
            if (church == null) {
                com.buildmain.services.MonitoringService.error("DONATION", "Paroisse introuvable: ${req.churchId}")
                call.respond(HttpStatusCode.NotFound, ApiErrorResponse("Paroisse introuvable: ${req.churchId}"))
                return@post
            }

            // 2. Vérification de la campagne de collecte
            val campaign = DatabaseFactory.findCampaignById(req.campaignId)
            if (campaign == null || !campaign.isActive) {
                com.buildmain.services.MonitoringService.error("DONATION", "Campagne introuvable ou inactive: ${req.campaignId} (Paroisse: ${church.name})")
                call.respond(
                    HttpStatusCode.BadRequest,
                    ApiErrorResponse("Campagne de collecte introuvable ou inactive: ${req.campaignId}")
                )
                return@post
            }

            // 3. Génération d'une référence unique (5 à 100 caractères selon spec Jèko)
            val donationId = UUID.randomUUID().toString()
            val reference = "BM-${UUID.randomUUID().toString().replace("-", "").take(8).uppercase()}"

            // 4. Enregistrement préalable du don avec statut PENDING
            DatabaseFactory.createDonation(
                id = donationId,
                churchId = church.id,
                campaignId = campaign.id,
                reference = reference,
                amount = req.amount,
                paymentMethod = req.paymentMethod.name.lowercase(),
                donorName = req.donorName ?: "Fidèle Anonyme",
                donorPhone = req.donorPhone
            )

            // Sécurisation des URLs de redirection (Jèko requiert des URLs valides non-localhost)
            val finalSuccessUrl = if (req.successUrl.contains("localhost")) {
                "https://buildmain.ci/don/${church.slug}/succes"
            } else {
                req.successUrl
            }
            val finalErrorUrl = if (req.errorUrl.contains("localhost")) {
                "https://buildmain.ci/don/${church.slug}/erreur"
            } else {
                req.errorUrl
            }

            // 5. Construction du payload Jèko conforme au schéma officiel MCP
            val jekoRequest = JekoPaymentRequest(
                amountCents = req.amount * 100L, // 1 XOF = 100 centimes
                currency = "XOF",
                reference = reference,
                storeId = church.jekoStoreId,
                paymentDetails = JekoPaymentDetails(
                    type = JekoPaymentType.REDIRECT,
                    data = JekoPaymentData(
                        paymentMethod = req.paymentMethod,
                        successUrl = finalSuccessUrl,
                        errorUrl = finalErrorUrl,
                        payerPhone = req.donorPhone
                    )
                )
            )

            try {
                // 6. Appel de l'API d'encaissement Jèko avec les identifiants de l'église
                val jekoResponse = jekoPaymentService.initiatePayIn(
                    apiKey = church.jekoApiKey,
                    apiKeyId = church.jekoApiKeyId,
                    request = jekoRequest
                )

                // 7. Mise à jour du don avec l'identifiant Jèko et l'URL de redirection
                DatabaseFactory.updateDonationPaymentInfo(
                    donationId = donationId,
                    jekoPaymentRequestId = jekoResponse.id,
                    redirectUrl = jekoResponse.redirectUrl
                )

                com.buildmain.services.MonitoringService.success(
                    category = "DONATION",
                    message = "Don $reference enregistré avec succès (${req.amount} FCFA via ${req.paymentMethod})",
                    details = "Redirect: ${jekoResponse.redirectUrl}"
                )

                call.respond(
                    HttpStatusCode.Created,
                    InitiateDonationResponse(
                        donationId = donationId,
                        reference = reference,
                        status = "PENDING",
                        amount = req.amount,
                        redirectUrl = jekoResponse.redirectUrl
                    )
                )
            } catch (e: Exception) {
                com.buildmain.services.MonitoringService.error(
                    category = "DONATION",
                    message = "Échec d'initiation Jèko pour don $reference",
                    details = e.message
                )
                logger.error("Erreur lors de l'initiation de paiement auprès de Jèko", e)
                call.respond(
                    HttpStatusCode.BadGateway,
                    ApiErrorResponse("Impossible d'initialiser le paiement Jèko: ${e.message}")
                )
            }
        }
    }
}
