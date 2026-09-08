package com.buildmain.config

import com.buildmain.models.Campaign
import com.buildmain.models.Campaigns
import com.buildmain.models.Church
import com.buildmain.models.Churches
import com.buildmain.models.Donation
import com.buildmain.models.Donations
import com.buildmain.models.CampaignStat
import com.buildmain.models.ChurchStats
import com.buildmain.models.MethodStat
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.Clock
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.or
import com.buildmain.utils.SecurityUtils
import org.slf4j.LoggerFactory
import java.util.UUID

object DatabaseFactory {
    private val logger = LoggerFactory.getLogger(DatabaseFactory::class.java)

    fun init(
        jdbcUrl: String? = EnvConfig.get("DB_JDBC_URL"),
        driverClassName: String? = EnvConfig.get("DB_DRIVER"),
        username: String = EnvConfig.get("DB_USER", "") ?: "",
        password: String = EnvConfig.get("DB_PASSWORD", "") ?: ""
    ) {
        val resolvedUrl = jdbcUrl ?: "jdbc:sqlite:build_main.db"
        val isSqlite = resolvedUrl.startsWith("jdbc:sqlite")
        val resolvedDriver = driverClassName ?: if (isSqlite) "org.sqlite.JDBC" else "org.postgresql.Driver"

        val config = HikariConfig().apply {
            this.jdbcUrl = resolvedUrl
            this.driverClassName = resolvedDriver
            if (isSqlite) {
                // SQLite requiert 1 seule connexion pour éviter les locks et SERIALIZABLE
                maximumPoolSize = 1
                transactionIsolation = "TRANSACTION_SERIALIZABLE"
                addDataSourceProperty("busy_timeout", "10000")
            } else {
                this.username = username
                this.password = password
                maximumPoolSize = 10
                transactionIsolation = "TRANSACTION_REPEATABLE_READ"
            }
            isAutoCommit = false
            validate()
        }

        val dataSource = HikariDataSource(config)
        Database.connect(dataSource)

        transaction {
            SchemaUtils.create(Churches, Campaigns, Donations)
            seedDemoDataIfEmpty()
        }
        logger.info("Base de données initialisée avec succès ($resolvedUrl).")
    }

    private fun seedDemoDataIfEmpty() {
        val churchId = "chu_abidjan_01"
        val storeId = (EnvConfig.get("JEKO_DEFAULT_STORE_ID") ?: "e0d9b7c0-d0f4-4dff-b487-2cb5ac5f30ed").trim()
        val apiKey = (EnvConfig.get("JEKO_DEFAULT_API_KEY") ?: "jeko_6f5bd5c3c35cb1709bd7a6d1b6fd4af6c2af78b46d11a1f2450da23965a97fe3").trim()
        val apiKeyId = (EnvConfig.get("JEKO_DEFAULT_API_KEY_ID") ?: "e00b005e-9277-46e3-bc83-2c4b34466bdf").trim()
        val webhookSecret = (EnvConfig.get("JEKO_DEFAULT_WEBHOOK_SECRET") ?: "5020dd974c4b185b807ec325d8f249d9efbc25454c906ebe3e7cf3be4bd14c45").trim()

        val demoAdminEmail = "admin@grace-et-verite.ci"
        val demoPasswordHash = SecurityUtils.hashPassword("Admin1234!")

        if (Churches.selectAll().where { Churches.id eq churchId }.count() == 0L) {
            Churches.insert {
                it[id] = churchId
                it[name] = "Paroisse Grâce & Vérité Cocody"
                it[slug] = "grace-et-verite-cocody"
                it[city] = "Abidjan"
                it[adminEmail] = demoAdminEmail
                it[passwordHash] = demoPasswordHash
                it[jekoStoreId] = storeId
                it[jekoApiKey] = apiKey
                it[jekoApiKeyId] = apiKeyId
                it[jekoWebhookSecret] = webhookSecret
                it[createdAt] = Clock.System.now()
            }
            logger.info("Paroisse démo $churchId insérée avec les clés Jèko actives et compte admin.")
        } else {
            // Forcer la synchronisation des clés et identifiants admin dans la base de données existante
            Churches.update({ Churches.id eq churchId }) {
                it[jekoStoreId] = storeId
                it[jekoApiKey] = apiKey
                it[jekoApiKeyId] = apiKeyId
                it[jekoWebhookSecret] = webhookSecret
                it[adminEmail] = demoAdminEmail
                it[passwordHash] = demoPasswordHash
            }
            logger.info("Paroisse démo $churchId synchronisée avec les clés actives (API_KEY_ID=$apiKeyId) et mot de passe démo.")
        }

        // Définition des campagnes requises pour la paroisse
        data class CampaignSeed(val id: String, val title: String, val type: String, val desc: String, val target: Long)
        val defaultCampaigns = listOf(
            CampaignSeed(
                id = "camp_offering_01",
                title = "Offrande Ordinaire du Culte",
                type = "OFFERING",
                desc = "Collecte des cultes dominicaux et réunions de prière",
                target = 2_000_000L
            ),
            CampaignSeed(
                id = "camp_tithe_01",
                title = "Dîmes du Dimanche & Prémices",
                type = "TITHE",
                desc = "Fidélité et bénédiction (Malachie 3:10)",
                target = 10_000_000L
            ),
            CampaignSeed(
                id = "camp_building_01",
                title = "Fonds de Construction Nouveau Sanctuaire",
                type = "BUILDING_FUND",
                desc = "Projet d'édification du nouveau temple 2000 places",
                target = 50_000_000L
            ),
            CampaignSeed(
                id = "camp_harvest_01",
                title = "Fête des Moissons Pastorale",
                type = "HARVEST",
                desc = "Action de grâce et récoltes annuelles",
                target = 15_000_000L
            )
        )

        for (c in defaultCampaigns) {
            if (Campaigns.selectAll().where { Campaigns.id eq c.id }.count() == 0L) {
                Campaigns.insert {
                    it[id] = c.id
                    it[this.churchId] = churchId
                    it[title] = c.title
                    it[type] = c.type
                    it[description] = c.desc
                    it[targetAmount] = c.target
                    it[isActive] = true
                    it[createdAt] = Clock.System.now()
                }
            }
        }
    }

    suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }

    // ========================================================================
    // Helpers Métier
    // ========================================================================

    suspend fun findChurchById(id: String): Church? = dbQuery {
        Churches.selectAll().where { Churches.id eq id }
            .map {
                Church(
                    id = it[Churches.id],
                    name = it[Churches.name],
                    slug = it[Churches.slug],
                    city = it[Churches.city],
                    jekoStoreId = it[Churches.jekoStoreId],
                    jekoApiKey = it[Churches.jekoApiKey],
                    jekoApiKeyId = it[Churches.jekoApiKeyId],
                    jekoWebhookSecret = it[Churches.jekoWebhookSecret],
                    adminEmail = it[Churches.adminEmail],
                    createdAt = it[Churches.createdAt].toString()
                )
            }.singleOrNull()
    }

    suspend fun findCampaignById(id: String): Campaign? = dbQuery {
        Campaigns.selectAll().where { Campaigns.id eq id }
            .map {
                Campaign(
                    id = it[Campaigns.id],
                    churchId = it[Campaigns.churchId],
                    title = it[Campaigns.title],
                    type = it[Campaigns.type],
                    description = it[Campaigns.description],
                    targetAmount = it[Campaigns.targetAmount],
                    isActive = it[Campaigns.isActive],
                    createdAt = it[Campaigns.createdAt].toString()
                )
            }.singleOrNull()
    }

    suspend fun createDonation(
        id: String,
        churchId: String,
        campaignId: String,
        reference: String,
        amount: Long,
        paymentMethod: String,
        donorName: String?,
        donorPhone: String?
    ): Donation = dbQuery {
        val now = Clock.System.now()
        val amountCents = amount * 100 // Conversion en centimes pour Jèko

        Donations.insert {
            it[Donations.id] = id
            it[Donations.churchId] = churchId
            it[Donations.campaignId] = campaignId
            it[Donations.reference] = reference
            it[Donations.amount] = amount
            it[Donations.amountCents] = amountCents
            it[Donations.paymentMethod] = paymentMethod
            it[Donations.donorName] = donorName
            it[Donations.donorPhone] = donorPhone
            it[Donations.status] = "PENDING"
            it[Donations.createdAt] = now
            it[Donations.updatedAt] = now
        }

        Donation(
            id = id,
            churchId = churchId,
            campaignId = campaignId,
            reference = reference,
            amount = amount,
            amountCents = amountCents,
            paymentMethod = paymentMethod,
            donorName = donorName,
            donorPhone = donorPhone,
            status = "PENDING",
            createdAt = now.toString(),
            updatedAt = now.toString()
        )
    }

    suspend fun updateDonationPaymentInfo(
        donationId: String,
        jekoPaymentRequestId: String,
        redirectUrl: String
    ): Unit = dbQuery {
        Donations.update({ Donations.id eq donationId }) {
            it[Donations.jekoPaymentRequestId] = jekoPaymentRequestId
            it[Donations.redirectUrl] = redirectUrl
            it[Donations.updatedAt] = Clock.System.now()
        }
    }

    suspend fun findDonationByReference(reference: String): Donation? = dbQuery {
        Donations.selectAll().where { Donations.reference eq reference }
            .map {
                Donation(
                    id = it[Donations.id],
                    churchId = it[Donations.churchId],
                    campaignId = it[Donations.campaignId],
                    reference = it[Donations.reference],
                    amount = it[Donations.amount],
                    amountCents = it[Donations.amountCents],
                    paymentMethod = it[Donations.paymentMethod],
                    donorName = it[Donations.donorName],
                    donorPhone = it[Donations.donorPhone],
                    status = it[Donations.status],
                    jekoPaymentRequestId = it[Donations.jekoPaymentRequestId],
                    jekoTransactionId = it[Donations.jekoTransactionId],
                    redirectUrl = it[Donations.redirectUrl],
                    createdAt = it[Donations.createdAt].toString(),
                    updatedAt = it[Donations.updatedAt].toString()
                )
            }.singleOrNull()
    }

    suspend fun markDonationAsSuccess(
        reference: String,
        jekoTransactionId: String
    ): Donation? = dbQuery {
        val now = Clock.System.now()
        Donations.update({ Donations.reference eq reference }) {
            it[Donations.status] = "SUCCESS"
            it[Donations.jekoTransactionId] = jekoTransactionId
            it[Donations.updatedAt] = now
        }
        findDonationByReference(reference)
    }

    suspend fun findChurchBySlug(slug: String): Church? = dbQuery {
        Churches.selectAll().where { Churches.slug eq slug }
            .map {
                Church(
                    id = it[Churches.id],
                    name = it[Churches.name],
                    slug = it[Churches.slug],
                    city = it[Churches.city],
                    jekoStoreId = it[Churches.jekoStoreId],
                    jekoApiKey = it[Churches.jekoApiKey],
                    jekoApiKeyId = it[Churches.jekoApiKeyId],
                    jekoWebhookSecret = it[Churches.jekoWebhookSecret],
                    adminEmail = it[Churches.adminEmail],
                    createdAt = it[Churches.createdAt].toString()
                )
            }.singleOrNull()
    }

    suspend fun findCampaignsByChurch(churchId: String): List<Campaign> = dbQuery {
        Campaigns.selectAll().where { Campaigns.churchId eq churchId }
            .map {
                Campaign(
                    id = it[Campaigns.id],
                    churchId = it[Campaigns.churchId],
                    title = it[Campaigns.title],
                    type = it[Campaigns.type],
                    description = it[Campaigns.description],
                    targetAmount = it[Campaigns.targetAmount],
                    isActive = it[Campaigns.isActive],
                    createdAt = it[Campaigns.createdAt].toString()
                )
            }
    }

    suspend fun findDonationsByChurch(churchId: String, limit: Int = 50): List<Donation> = dbQuery {
        Donations.selectAll().where { Donations.churchId eq churchId }
            .orderBy(Donations.createdAt, SortOrder.DESC)
            .limit(limit)
            .map {
                Donation(
                    id = it[Donations.id],
                    churchId = it[Donations.churchId],
                    campaignId = it[Donations.campaignId],
                    reference = it[Donations.reference],
                    amount = it[Donations.amount],
                    amountCents = it[Donations.amountCents],
                    paymentMethod = it[Donations.paymentMethod],
                    donorName = it[Donations.donorName],
                    donorPhone = it[Donations.donorPhone],
                    status = it[Donations.status],
                    jekoPaymentRequestId = it[Donations.jekoPaymentRequestId],
                    jekoTransactionId = it[Donations.jekoTransactionId],
                    redirectUrl = it[Donations.redirectUrl],
                    createdAt = it[Donations.createdAt].toString(),
                    updatedAt = it[Donations.updatedAt].toString()
                )
            }
    }

    suspend fun getChurchStats(churchId: String): ChurchStats = dbQuery {
        val allDonations = Donations.selectAll().where { Donations.churchId eq churchId }.toList()
        val allCampaigns = Campaigns.selectAll().where { Campaigns.churchId eq churchId }.associateBy { it[Campaigns.id] }

        val totalCollected = allDonations.filter { it[Donations.status] == "SUCCESS" }.sumOf { it[Donations.amount] }
        val totalTransactions = allDonations.size.toLong()
        val successfulTransactions = allDonations.count { it[Donations.status] == "SUCCESS" }.toLong()

        val campaignsStats = allCampaigns.values.map { cRow ->
            val cId = cRow[Campaigns.id]
            val cDonations = allDonations.filter { it[Donations.campaignId] == cId && it[Donations.status] == "SUCCESS" }
            CampaignStat(
                campaignId = cId,
                campaignTitle = cRow[Campaigns.title],
                campaignType = cRow[Campaigns.type],
                totalAmount = cDonations.sumOf { it[Donations.amount] },
                donationCount = cDonations.size.toLong()
            )
        }

        val methodStats = allDonations.filter { it[Donations.status] == "SUCCESS" }
            .groupBy { it[Donations.paymentMethod] }
            .map { (method, dons) ->
                MethodStat(
                    method = method,
                    totalAmount = dons.sumOf { it[Donations.amount] },
                    count = dons.size.toLong()
                )
            }

        ChurchStats(
            churchId = churchId,
            totalCollected = totalCollected,
            totalTransactions = totalTransactions,
            successfulTransactions = successfulTransactions,
            campaigns = campaignsStats,
            methods = methodStats
        )
    }

    suspend fun createChurch(
        name: String,
        slug: String,
        city: String = "Abidjan",
        adminEmail: String? = null,
        password: String? = null,
        jekoStoreId: String? = null,
        jekoApiKey: String? = null,
        jekoApiKeyId: String? = null,
        jekoWebhookSecret: String? = null
    ): Church = dbQuery {
        val cleanSlug = slug.trim().lowercase()
        val existing = Churches.selectAll().where { Churches.slug eq cleanSlug }.count()
        if (existing > 0L) {
            throw IllegalArgumentException("Une organisation avec l'identifiant '$cleanSlug' existe déjà.")
        }

        val churchId = "chu_" + UUID.randomUUID().toString().replace("-", "").take(12)
        val defaultStoreId = (EnvConfig.get("JEKO_DEFAULT_STORE_ID") ?: "e0d9b7c0-d0f4-4dff-b487-2cb5ac5f30ed").trim()
        val defaultApiKey = (EnvConfig.get("JEKO_DEFAULT_API_KEY") ?: "jeko_6f5bd5c3c35cb1709bd7a6d1b6fd4af6c2af78b46d11a1f2450da23965a97fe3").trim()
        val defaultApiKeyId = (EnvConfig.get("JEKO_DEFAULT_API_KEY_ID") ?: "e00b005e-9277-46e3-bc83-2c4b34466bdf").trim()
        val defaultWebhookSecret = (EnvConfig.get("JEKO_DEFAULT_WEBHOOK_SECRET") ?: "5020dd974c4b185b807ec325d8f249d9efbc25454c906ebe3e7cf3be4bd14c45").trim()

        val finalStoreId = if (!jekoStoreId.isNullOrBlank()) jekoStoreId.trim() else defaultStoreId
        val finalApiKey = if (!jekoApiKey.isNullOrBlank()) jekoApiKey.trim() else defaultApiKey
        val finalApiKeyId = if (!jekoApiKeyId.isNullOrBlank()) jekoApiKeyId.trim() else defaultApiKeyId
        val finalWebhookSecret = if (!jekoWebhookSecret.isNullOrBlank()) jekoWebhookSecret.trim() else defaultWebhookSecret

        val cleanEmail = adminEmail?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        val pwdHash = password?.takeIf { it.isNotBlank() }?.let { SecurityUtils.hashPassword(it) }

        val now = Clock.System.now()
        Churches.insert {
            it[id] = churchId
            it[Churches.name] = name.trim()
            it[Churches.slug] = cleanSlug
            it[Churches.city] = city.trim()
            it[Churches.adminEmail] = cleanEmail
            it[Churches.passwordHash] = pwdHash
            it[Churches.jekoStoreId] = finalStoreId
            it[Churches.jekoApiKey] = finalApiKey
            it[Churches.jekoApiKeyId] = finalApiKeyId
            it[Churches.jekoWebhookSecret] = finalWebhookSecret
            it[createdAt] = now
        }

        // Création automatique des 3 campagnes initiales
        val initialCampaigns = listOf(
            Triple("Dîme", "TITHE", "Prémices & fidélité (Malachie 3:10)"),
            Triple("Offrande de culte", "OFFERING", "Offrandes ordinaires et actions de grâce"),
            Triple("Projet de construction & travaux", "BUILDING_FUND", "Souscription pour l'édification et l'équipement du sanctuaire")
        )

        for ((title, type, desc) in initialCampaigns) {
            val campId = "camp_" + UUID.randomUUID().toString().replace("-", "").take(12)
            val target = if (type == "BUILDING_FUND") 10_000_000L else null
            Campaigns.insert {
                it[id] = campId
                it[Campaigns.churchId] = churchId
                it[Campaigns.title] = title
                it[Campaigns.type] = type
                it[Campaigns.description] = desc
                it[Campaigns.targetAmount] = target
                it[Campaigns.isActive] = true
                it[createdAt] = now
            }
        }

        Church(
            id = churchId,
            name = name.trim(),
            slug = cleanSlug,
            city = city.trim(),
            jekoStoreId = finalStoreId,
            jekoApiKey = finalApiKey,
            jekoApiKeyId = finalApiKeyId,
            jekoWebhookSecret = finalWebhookSecret,
            adminEmail = cleanEmail,
            createdAt = now.toString()
        )
    }

    suspend fun createCampaign(
        churchId: String,
        title: String,
        type: String,
        description: String?,
        targetAmount: Long?
    ): Campaign = dbQuery {
        val campId = "camp_" + UUID.randomUUID().toString().replace("-", "").take(12)
        val now = Clock.System.now()
        Campaigns.insert {
            it[id] = campId
            it[Campaigns.churchId] = churchId
            it[Campaigns.title] = title.trim()
            it[Campaigns.type] = type.trim()
            it[Campaigns.description] = description?.trim()
            it[Campaigns.targetAmount] = targetAmount
            it[Campaigns.isActive] = true
            it[createdAt] = now
        }
        Campaign(
            id = campId,
            churchId = churchId,
            title = title.trim(),
            type = type.trim(),
            description = description?.trim(),
            targetAmount = targetAmount,
            isActive = true,
            createdAt = now.toString()
        )
    }

    suspend fun updateCampaign(
        campaignId: String,
        title: String?,
        type: String?,
        description: String?,
        targetAmount: Long?,
        isActive: Boolean?
    ): Campaign? = dbQuery {
        Campaigns.update({ Campaigns.id eq campaignId }) {
            if (title != null) it[Campaigns.title] = title.trim()
            if (type != null) it[Campaigns.type] = type.trim()
            if (description != null) it[Campaigns.description] = description.trim()
            if (targetAmount != null) it[Campaigns.targetAmount] = targetAmount
            if (isActive != null) it[Campaigns.isActive] = isActive
        }
        findCampaignById(campaignId)
    }

    suspend fun toggleCampaignStatus(campaignId: String): Campaign? = dbQuery {
        val current = Campaigns.selectAll().where { Campaigns.id eq campaignId }.singleOrNull() ?: return@dbQuery null
        val newStatus = !current[Campaigns.isActive]
        Campaigns.update({ Campaigns.id eq campaignId }) {
            it[isActive] = newStatus
        }
        findCampaignById(campaignId)
    }

    suspend fun deleteCampaign(campaignId: String): Boolean = dbQuery {
        val deletedRows = Campaigns.deleteWhere { Campaigns.id eq campaignId }
        deletedRows > 0
    }

    suspend fun authenticateChurch(login: String, rawPassword: String): Pair<Church, String>? = dbQuery {
        val cleanLogin = login.trim().lowercase()
        val row = Churches.selectAll().where {
            (Churches.adminEmail eq cleanLogin) or (Churches.slug eq cleanLogin)
        }.singleOrNull() ?: return@dbQuery null

        val storedHash = row[Churches.passwordHash] ?: return@dbQuery null
        if (!SecurityUtils.verifyPassword(rawPassword, storedHash)) {
            return@dbQuery null
        }

        val church = Church(
            id = row[Churches.id],
            name = row[Churches.name],
            slug = row[Churches.slug],
            city = row[Churches.city],
            jekoStoreId = row[Churches.jekoStoreId],
            jekoApiKey = row[Churches.jekoApiKey],
            jekoApiKeyId = row[Churches.jekoApiKeyId],
            jekoWebhookSecret = row[Churches.jekoWebhookSecret],
            adminEmail = row[Churches.adminEmail],
            createdAt = row[Churches.createdAt].toString()
        )

        val secret = EnvConfig.get("JWT_SECRET", "buildmain_super_secret_jwt_key_2026") ?: "buildmain_super_secret_jwt_key_2026"
        val token = SecurityUtils.generateAuthToken(church.id, church.adminEmail ?: church.slug, secret)

        Pair(church, token)
    }

    suspend fun verifyAuthHeader(header: String?): Church? {
        if (header.isNullOrBlank() || !header.startsWith("Bearer ")) return null
        val token = header.removePrefix("Bearer ").trim()
        val secret = EnvConfig.get("JWT_SECRET", "buildmain_super_secret_jwt_key_2026") ?: "buildmain_super_secret_jwt_key_2026"
        val verified = SecurityUtils.verifyAuthToken(token, secret) ?: return null
        return findChurchById(verified.first)
    }
}

