package com.buildmain.models.jeko

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Modèle monétaire standard Jèko (ISO 4217).
 * Les montants sont exprimés dans la plus petite unité de la devise (centimes pour XOF).
 * Exemple: 10 000 centimes = 100 XOF.
 */
@Serializable
data class JekoMoney(
    val amount: Long,
    val currency: String = "XOF"
)

// ============================================================================
// 1. CRÉATION D'UNE SESSION DE PAIEMENT / CHECKOUT (POST /partner_api/payment_requests)
// ============================================================================

/**
 * Méthodes de paiement supportées par la passerelle Jèko en Côte d'Ivoire.
 */
@Serializable
enum class JekoPaymentMethod {
    @SerialName("orange")
    ORANGE,

    @SerialName("wave")
    WAVE,

    @SerialName("mtn")
    MTN,

    @SerialName("moov")
    MOOV,

    @SerialName("djamo")
    DJAMO,

    @SerialName("bank")
    BANK
}

/**
 * Type d'encaissement Jèko.
 */
@Serializable
enum class JekoPaymentType {
    @SerialName("redirect")
    REDIRECT,

    @SerialName("soundbox")
    SOUNDBOX
}

/**
 * Statuts d'une demande de paiement ou transaction Jèko.
 */
@Serializable
enum class JekoPaymentStatus {
    @SerialName("pending")
    PENDING,

    @SerialName("success")
    SUCCESS,

    @SerialName("error")
    ERROR
}

/**
 * Données spécifiques au canal de paiement Jèko.
 */
@Serializable
data class JekoPaymentData(
    val paymentMethod: JekoPaymentMethod,
    val successUrl: String,
    val errorUrl: String,
    val deviceId: String? = null,
    val forceProviderDirect: Boolean? = null,
    val payerPhone: String? = null
)

/**
 * Détails de configuration du paiement (type et données associées).
 */
@Serializable
data class JekoPaymentDetails(
    val type: JekoPaymentType = JekoPaymentType.REDIRECT,
    val data: JekoPaymentData
)

/**
 * Payload de requête pour initialiser un paiement (Pay-in) via l'API Partenaire Jèko.
 * Endpoint: POST /partner_api/payment_requests
 * Headers requis: X-API-KEY, X-API-KEY-ID, Content-Type: application/json
 */
@Serializable
data class JekoPaymentRequest(
    val amountCents: Long,
    val currency: String = "XOF",
    val reference: String,
    val storeId: String,
    val paymentDetails: JekoPaymentDetails
)

/**
 * Résumé de transaction rattaché à la réponse de création de paiement.
 */
@Serializable
data class JekoTransactionSummary(
    val id: String,
    val amount: JekoMoney,
    val fees: JekoMoney,
    val status: JekoPaymentStatus,
    val counterpartLabel: String? = null,
    val counterpartIdentifier: String? = null,
    val description: String? = null,
    val executedAt: String? = null
)

/**
 * Réponse renvoyée par Jèko après la création d'une session de paiement.
 * Contient l'URL de redirection vers laquelle le fidèle/client doit être redirigé.
 */
@Serializable
data class JekoPaymentResponse(
    val id: String,
    val storeId: String,
    val reference: String,
    val type: JekoPaymentType,
    val paymentMethod: JekoPaymentMethod,
    val status: JekoPaymentStatus,
    val redirectUrl: String,
    val errorReason: String? = null,
    val transaction: JekoTransactionSummary? = null
)

// ============================================================================
// 2. CRÉATION D'UN LIEN DE PAIEMENT RÉUTILISABLE (POST /partner_api/payment_links)
// ============================================================================

/**
 * Payload de requête pour créer un lien de paiement Jèko partageable.
 * Endpoint: POST /partner_api/payment_links
 */
@Serializable
data class JekoPaymentLinkRequest(
    val storeId: String,
    val title: String,
    val amountCents: Long,
    val currency: String = "XOF",
    val allowMultiplePayments: Boolean? = null
)

/**
 * Réponse renvoyée après la création d'un lien de paiement.
 */
@Serializable
data class JekoPaymentLinkResponse(
    val id: String,
    val storeId: String,
    val title: String,
    val amount: JekoMoney,
    val allowMultiplePayments: Boolean,
    val canReceivePayments: Boolean,
    val link: String
)

// ============================================================================
// 3. WEBHOOKS DE CONFIRMATION (TRANSACTION_COMPLETED & ATTESTATIONS)
// ============================================================================

/**
 * Détails additionnels inclus dans le webhook de confirmation d'une transaction.
 */
@Serializable
data class JekoTransactionDetails(
    val id: String? = null,
    val reference: String? = null,
    val paymentLinkId: String? = null
)

/**
 * Payload reçu lors d'un événement TRANSACTION_COMPLETED.
 * RÈGLE D'OR JÈKO : Le corps est "plat", sans enveloppe JSON globale ni champ 'event'.
 * L'authenticité DOIT être validée via l'en-tête HTTP 'Jeko-Signature' (HMAC-SHA256 du raw body).
 */
@Serializable
data class JekoWebhookTransaction(
    val id: String,
    val amount: JekoMoney,
    val fees: JekoMoney,
    val status: JekoPaymentStatus,
    val counterpartLabel: String? = null,
    val counterpartIdentifier: String? = null,
    val paymentMethod: JekoPaymentMethod,
    val transactionType: String = "PaymentRequest",
    val businessName: String? = null,
    val storeName: String? = null,
    val description: String? = null,
    val executedAt: String? = null,
    val transactionDetails: JekoTransactionDetails? = null
)

/**
 * Enveloppe pour les événements d'infrastructure ou de liaison Service Provider
 * (ex: SERVICE_PROVIDER_LINK_REQUEST).
 */
@Serializable
data class JekoWebhookEnvelope(
    val event: String,
    val payload: JekoWebhookTransaction? = null
)
