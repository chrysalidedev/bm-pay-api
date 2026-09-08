package com.buildmain.routes

import com.buildmain.config.DatabaseFactory
import com.buildmain.models.DonationSuccessEvent
import com.buildmain.models.jeko.JekoPaymentStatus
import com.buildmain.models.jeko.JekoWebhookSecurity
import com.buildmain.models.jeko.JekoWebhookTransaction
import com.buildmain.services.LiveGivingManager
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

@Serializable
data class WebhookResponse(
    val received: Boolean,
    val message: String? = null
)

fun Route.webhookRoutes() {
    val logger = LoggerFactory.getLogger("WebhookRoutes")
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    route("/api/webhooks") {
        /**
         * POST /api/webhooks/jeko
         * Réceptionne les notifications TRANSACTION_COMPLETED de Jèko.
         * 1. Lit le raw body brut et l'en-tête 'Jeko-Signature'.
         * 2. Rapproche la transaction avec le don en base pour obtenir le secret de l'église.
         * 3. Valide cryptographiquement le HMAC-SHA256 en temps constant.
         * 4. Met à jour le don à SUCCESS.
         * 5. Diffuse instantanément l'événement aux écrans de culte via WebSockets.
         * 6. Répond 200 OK sous 5 secondes.
         */
        post("/jeko") {
            // 1. Récupération du raw body obligatoire pour le calcul HMAC
            val rawBody = try {
                call.receive<ByteArray>()
            } catch (e: Exception) {
                com.buildmain.services.MonitoringService.error("WEBHOOK", "Impossible de lire le corps brut du webhook", e.message)
                logger.error("Impossible de lire le corps brut du webhook: ${e.message}")
                call.respond(HttpStatusCode.BadRequest, WebhookResponse(received = false, message = "Corps vide ou illisible"))
                return@post
            }

            // 2. Récupération de la signature Jèko
            val receivedSignature = call.request.header("Jeko-Signature")
                ?: call.request.header("jeko-signature")

            com.buildmain.services.MonitoringService.info(
                category = "WEBHOOK",
                message = "Webhook Jèko reçu (${rawBody.size} octets)",
                details = "Header Jeko-Signature: ${receivedSignature ?: "ABSENT"}"
            )

            if (receivedSignature.isNullOrBlank()) {
                com.buildmain.services.MonitoringService.warn("WEBHOOK", "Webhook rejeté : En-tête Jeko-Signature manquant")
                logger.warn("Webhook rejeté : En-tête Jeko-Signature manquant")
                call.respond(HttpStatusCode.Unauthorized, WebhookResponse(received = false, message = "Header Jeko-Signature absent"))
                return@post
            }

            // 3. Décodage du payload transaction (Jèko envoie un payload plat, sans enveloppe)
            val payloadString = rawBody.decodeToString()
            val webhook = try {
                json.decodeFromString<JekoWebhookTransaction>(payloadString)
            } catch (e: Exception) {
                com.buildmain.services.MonitoringService.error("WEBHOOK", "Erreur de parsing JSON du webhook", e.message)
                logger.error("Erreur de parsing JSON du webhook: ${e.message}\nPayload: $payloadString")
                call.respond(HttpStatusCode.BadRequest, WebhookResponse(received = false, message = "JSON malformé"))
                return@post
            }

            // 4. Extraction de la référence du don
            val reference = webhook.transactionDetails?.reference
            if (reference.isNullOrBlank()) {
                com.buildmain.services.MonitoringService.warn("WEBHOOK", "Webhook sans transactionDetails.reference (ID Jèko: ${webhook.id})")
                logger.warn("Webhook Jèko sans référence de transaction (ID Jèko: ${webhook.id})")
                call.respond(HttpStatusCode.BadRequest, WebhookResponse(received = false, message = "Référence introuvable"))
                return@post
            }

            // 5. Recherche du don et de la paroisse associée
            val donation = DatabaseFactory.findDonationByReference(reference)
            if (donation == null) {
                com.buildmain.services.MonitoringService.warn("WEBHOOK", "Donation introuvable en base pour la référence: $reference")
                logger.warn("Donation introuvable pour la référence Jèko: $reference")
                call.respond(HttpStatusCode.NotFound, WebhookResponse(received = false, message = "Donation introuvable"))
                return@post
            }

            val church = DatabaseFactory.findChurchById(donation.churchId)
            if (church == null) {
                com.buildmain.services.MonitoringService.error("WEBHOOK", "Paroisse introuvable pour le don ref=$reference")
                logger.error("Paroisse introuvable pour le don ref=$reference (churchId=${donation.churchId})")
                call.respond(HttpStatusCode.InternalServerError, WebhookResponse(received = false, message = "Paroisse introuvable"))
                return@post
            }

            // 6. Vérification cryptographique HMAC-SHA256 avec le secret de l'église
            val isSignatureValid = JekoWebhookSecurity.verifySignature(
                rawBody = rawBody,
                receivedSignature = receivedSignature,
                webhookSecret = church.jekoWebhookSecret
            )

            if (!isSignatureValid) {
                com.buildmain.services.MonitoringService.error(
                    category = "WEBHOOK",
                    message = "Signature HMAC-SHA256 INVALIDE pour réf $reference",
                    details = "Signature reçue=$receivedSignature"
                )
                logger.warn("Signature Jeko invalide pour la référence $reference !")
                call.respond(HttpStatusCode.Unauthorized, WebhookResponse(received = false, message = "Signature invalide"))
                return@post
            }

            com.buildmain.services.MonitoringService.success(
                category = "WEBHOOK",
                message = "Signature HMAC-SHA256 authentifiée avec succès pour réf $reference"
            )

            // 7. Traitement idempotent : si déjà validé, répondre 200 directement
            if (donation.status == "SUCCESS") {
                com.buildmain.services.MonitoringService.info("WEBHOOK", "Donation $reference déjà confirmée (idempotence)")
                logger.info("Donation $reference déjà confirmée (idempotence)")
                call.respond(HttpStatusCode.OK, WebhookResponse(received = true, message = "Déjà traité"))
                return@post
            }

            // 8. Mise à jour du statut si paiement réussi
            if (webhook.status == JekoPaymentStatus.SUCCESS) {
                val updatedDonation = DatabaseFactory.markDonationAsSuccess(
                    reference = reference,
                    jekoTransactionId = webhook.id
                )
                com.buildmain.services.MonitoringService.success(
                    category = "WEBHOOK",
                    message = "Paiement validé avec succès ! Don $reference (${donation.amount} FCFA via ${donation.paymentMethod})",
                    details = "ID Jèko: ${webhook.id}, Donateur: ${donation.donorName ?: "Anonyme"}"
                )
                logger.info("Donation $reference validée avec succès via Jèko (ID: ${webhook.id}) !")

                // 9. Diffusion WebSocket en direct pour les écrans de culte
                val campaign = DatabaseFactory.findCampaignById(donation.campaignId)
                LiveGivingManager.broadcast(
                    churchId = church.id,
                    event = DonationSuccessEvent(
                        donationId = donation.id,
                        churchId = church.id,
                        campaignId = donation.campaignId,
                        campaignTitle = campaign?.title ?: "Offrande Culte",
                        amount = donation.amount,
                        paymentMethod = donation.paymentMethod,
                        donorName = donation.donorName ?: "Fidèle Anonyme",
                        reference = donation.reference,
                        timestamp = Clock.System.now().toString()
                    )
                )
            } else {
                com.buildmain.services.MonitoringService.warn("WEBHOOK", "Statut Jèko non-succès pour $reference: ${webhook.status}")
                logger.warn("Statut Jèko non-succès reçu pour $reference : ${webhook.status}")
            }

            // 10. Accusé de réception 200 OK sous 5 secondes
            call.respond(HttpStatusCode.OK, WebhookResponse(received = true))
        }
    }
}
