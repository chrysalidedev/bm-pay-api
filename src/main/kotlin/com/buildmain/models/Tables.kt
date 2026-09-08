package com.buildmain.models

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

/**
 * Table des Paroisses / Assemblées (Multi-tenancy).
 * Stocke les identifiants et clés d'API Jèko propres à chaque communauté.
 */
object Churches : Table("churches") {
    val id = varchar("id", 36)
    val name = varchar("name", 255)
    val slug = varchar("slug", 100).uniqueIndex()
    val city = varchar("city", 100).default("Abidjan")
    val jekoStoreId = varchar("jeko_store_id", 100)
    val jekoApiKey = varchar("jeko_api_key", 255)
    val jekoApiKeyId = varchar("jeko_api_key_id", 255)
    val jekoWebhookSecret = varchar("jeko_webhook_secret", 255)
    val adminEmail = varchar("admin_email", 255).nullable()
    val passwordHash = varchar("password_hash", 255).nullable()
    val createdAt = timestamp("created_at").clientDefault { Clock.System.now() }

    override val primaryKey = PrimaryKey(id)
}

/**
 * Table des Campagnes et Types de Collectes.
 * (Dîmes, Offrandes de culte, Moisson pastorale, Fonds de construction d'édifice).
 */
object Campaigns : Table("campaigns") {
    val id = varchar("id", 36)
    val churchId = reference("church_id", Churches.id, onDelete = ReferenceOption.CASCADE)
    val title = varchar("title", 255)
    val type = varchar("type", 50) // TITHE, OFFERING, BUILDING_FUND, HARVEST, SPECIAL
    val description = text("description").nullable()
    val targetAmount = long("target_amount").nullable() // Objectif en FCFA (XOF)
    val isActive = bool("is_active").default(true)
    val createdAt = timestamp("created_at").clientDefault { Clock.System.now() }

    override val primaryKey = PrimaryKey(id)
}

/**
 * Table des Dons et Transactions Mobile Money.
 * Rapprochement direct avec les identifiants et webhooks Jèko.
 */
object Donations : Table("donations") {
    val id = varchar("id", 36)
    val churchId = reference("church_id", Churches.id, onDelete = ReferenceOption.CASCADE)
    val campaignId = reference("campaign_id", Campaigns.id, onDelete = ReferenceOption.CASCADE)
    val reference = varchar("reference", 100).uniqueIndex() // Référence unique BM-XXXXXX
    val amount = long("amount") // Montant en FCFA
    val amountCents = long("amount_cents") // Montant en centimes (amount * 100) pour Jèko
    val paymentMethod = varchar("payment_method", 50) // wave, orange, mtn, moov, djamo
    val donorName = varchar("donor_name", 255).nullable() // Anonymat paramétrable
    val donorPhone = varchar("donor_phone", 50).nullable() // Numéro Mobile Money (+225...)
    val status = varchar("status", 50).default("PENDING") // PENDING, SUCCESS, ERROR, CANCELLED
    val jekoPaymentRequestId = varchar("jeko_payment_request_id", 100).nullable()
    val jekoTransactionId = varchar("jeko_transaction_id", 100).nullable()
    val redirectUrl = text("redirect_url").nullable()
    val createdAt = timestamp("created_at").clientDefault { Clock.System.now() }
    val updatedAt = timestamp("updated_at").clientDefault { Clock.System.now() }

    override val primaryKey = PrimaryKey(id)
}

// ============================================================================
// DTOs & Entités Métier Sérialisables
// ============================================================================

@Serializable
data class Church(
    val id: String,
    val name: String,
    val slug: String,
    val city: String,
    val jekoStoreId: String,
    val jekoApiKey: String,
    val jekoApiKeyId: String,
    val jekoWebhookSecret: String,
    val adminEmail: String? = null,
    val createdAt: String
)

@Serializable
data class Campaign(
    val id: String,
    val churchId: String,
    val title: String,
    val type: String,
    val description: String? = null,
    val targetAmount: Long? = null,
    val isActive: Boolean = true,
    val createdAt: String
)

@Serializable
data class Donation(
    val id: String,
    val churchId: String,
    val campaignId: String,
    val reference: String,
    val amount: Long,
    val amountCents: Long,
    val paymentMethod: String,
    val donorName: String? = null,
    val donorPhone: String? = null,
    val status: String,
    val jekoPaymentRequestId: String? = null,
    val jekoTransactionId: String? = null,
    val redirectUrl: String? = null,
    val createdAt: String,
    val updatedAt: String
)

@Serializable
data class DonationSuccessEvent(
    val event: String = "DONATION_RECEIVED",
    val donationId: String,
    val churchId: String,
    val campaignId: String,
    val campaignTitle: String,
    val amount: Long,
    val paymentMethod: String,
    val donorName: String,
    val reference: String,
    val timestamp: String
)

@Serializable
data class CampaignStat(
    val campaignId: String,
    val campaignTitle: String,
    val campaignType: String,
    val totalAmount: Long,
    val donationCount: Long
)

@Serializable
data class MethodStat(
    val method: String,
    val totalAmount: Long,
    val count: Long
)

@Serializable
data class ChurchStats(
    val churchId: String,
    val totalCollected: Long,
    val totalTransactions: Long,
    val successfulTransactions: Long,
    val campaigns: List<CampaignStat>,
    val methods: List<MethodStat>
)

@Serializable
data class CreateChurchRequest(
    val name: String,
    val slug: String,
    val city: String = "Abidjan",
    val adminEmail: String? = null,
    val password: String? = null,
    val jekoStoreId: String? = null,
    val jekoApiKey: String? = null,
    val jekoApiKeyId: String? = null,
    val jekoWebhookSecret: String? = null
)

@Serializable
data class LoginRequest(
    val login: String, // email ou slug
    val password: String
)

@Serializable
data class LoginResponse(
    val token: String,
    val church: Church,
    val expiresIn: Long // en secondes
)

@Serializable
data class CreateCampaignRequest(
    val title: String,
    val type: String = "OFFERING",
    val description: String? = null,
    val targetAmount: Long? = null
)

@Serializable
data class UpdateCampaignRequest(
    val title: String? = null,
    val type: String? = null,
    val description: String? = null,
    val targetAmount: Long? = null,
    val isActive: Boolean? = null
)

