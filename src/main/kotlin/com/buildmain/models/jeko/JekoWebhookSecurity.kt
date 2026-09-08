package com.buildmain.models.jeko

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.Json

/**
 * Utilitaires de sécurité et de simulation pour les Webhooks Jèko.
 * Conforme aux spécifications du serveur MCP Jèko :
 * - Algorithme HMAC-SHA256 sur le corps brut (raw body).
 * - En-tête 'Jeko-Signature' encodé en hexadécimal minuscule sans préfixe.
 * - Comparaison en temps constant pour éviter les attaques par canal auxiliaire (timing attacks).
 */
object JekoWebhookSecurity {

    private const val HMAC_SHA256 = "HmacSHA256"

    /**
     * Calcule la signature HMAC-SHA256 d'un payload brut avec le secret webhook Jèko.
     */
    fun computeSignature(rawBody: ByteArray, secret: String): String {
        val keySpec = SecretKeySpec(secret.toByteArray(Charsets.UTF_8), HMAC_SHA256)
        val mac = Mac.getInstance(HMAC_SHA256).apply {
            init(keySpec)
        }
        val hash = mac.doFinal(rawBody)
        return hash.joinToString("") { "%02x".format(it) }
    }

    /**
     * Vérifie la validité de l'en-tête 'Jeko-Signature' de manière sécurisée (timing-safe).
     *
     * @param rawBody Le flux d'octets brut de la requête HTTP reçu par Ktor.
     * @param receivedSignature La valeur brute de l'en-tête 'Jeko-Signature'.
     * @param webhookSecret Le secret partagé configuré dans le dashboard Jèko Business.
     * @return true si la signature est authentique, false sinon.
     */
    fun verifySignature(rawBody: ByteArray, receivedSignature: String?, webhookSecret: String): Boolean {
        if (receivedSignature.isNullOrBlank() || webhookSecret.isBlank()) {
            return false
        }

        val expectedSignature = computeSignature(rawBody, webhookSecret)

        val receivedBytes = receivedSignature.trim().lowercase().toByteArray(Charsets.UTF_8)
        val expectedBytes = expectedSignature.toByteArray(Charsets.UTF_8)

        // Comparaison à temps constant
        return MessageDigest.isEqual(receivedBytes, expectedBytes)
    }

    /**
     * Générateur / Simulateur de payload Webhook Jèko pour les tests d'intégration locaux.
     */
    fun generateSimulatedWebhook(
        transactionId: String = "txn_${System.currentTimeMillis()}",
        reference: String,
        amountInCents: Long,
        paymentMethod: JekoPaymentMethod = JekoPaymentMethod.WAVE,
        status: JekoPaymentStatus = JekoPaymentStatus.SUCCESS,
        donorName: String = "Fidèle Anonyme",
        donorPhone: String = "+2250700000000",
        webhookSecret: String
    ): Pair<String, String> {
        val tx = JekoWebhookTransaction(
            id = transactionId,
            amount = JekoMoney(amount = amountInCents, currency = "XOF"),
            fees = JekoMoney(amount = (amountInCents * 0.015).toLong(), currency = "XOF"),
            status = status,
            counterpartLabel = donorName,
            counterpartIdentifier = donorPhone,
            paymentMethod = paymentMethod,
            transactionType = "PaymentRequest",
            businessName = "Build-Main Church SaaS",
            storeName = "Paroisse Principale",
            description = "Collecte de culte / Dîme - Ref $reference",
            executedAt = "2026-09-05 22:30:00",
            transactionDetails = JekoTransactionDetails(
                id = "req_$transactionId",
                reference = reference
            )
        )

        val json = Json {
            prettyPrint = false
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        val jsonString = json.encodeToString(JekoWebhookTransaction.serializer(), tx)
        val signature = computeSignature(jsonString.toByteArray(Charsets.UTF_8), webhookSecret)

        return Pair(jsonString, signature)
    }
}
