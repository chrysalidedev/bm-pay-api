package com.buildmain.config

import org.slf4j.LoggerFactory
import java.io.File

/**
 * Chargeur et gestionnaire de configuration multi-environnements (local, dev, prod).
 * Charge hiérarchiquement :
 *  1. Valeurs par défaut du code
 *  2. Fichier .env (base)
 *  3. Fichier .env.{env} (.env.local, .env.dev, .env.prod)
 *  4. Fichier .env.local (surcharges locales de la machine développeur)
 *  5. Variables d'environnement système (System.getenv() - priorité maximale)
 */
object EnvConfig {
    private val logger = LoggerFactory.getLogger(EnvConfig::class.java)
    private val envMap = mutableMapOf<String, String>()

    init {
        loadDotEnv()
    }

    private fun loadDotEnv() {
        // 1. Détecter l'environnement actif (priorité: variable système APP_ENV ou ENV, défaut: local)
        val initialEnv = (System.getenv("APP_ENV") ?: System.getenv("ENV") ?: "local").lowercase().trim()

        // 2. Répertoires de recherche possibles
        val searchDirs = listOf(
            File("."),
            File(".."),
            File("backend")
        ).filter { it.exists() && it.isDirectory }

        // Fichiers à charger par ordre de priorité croissante
        val fileOrder = listOf(
            ".env",
            ".env.$initialEnv",
            ".env.local"
        )

        val loadedFiles = mutableSetOf<String>()

        for (dir in searchDirs) {
            for (fileName in fileOrder) {
                val file = File(dir, fileName)
                if (file.exists() && file.isFile && !loadedFiles.contains(file.canonicalPath)) {
                    loadedFiles.add(file.canonicalPath)
                    logger.info("Chargement configuration d'environnement [$initialEnv] depuis: ${file.canonicalPath}")
                    file.readLines().forEach { line ->
                        val trimmed = line.trim()
                        if (trimmed.isNotEmpty() && !trimmed.startsWith("#") && trimmed.contains("=")) {
                            val parts = trimmed.split("=", limit = 2)
                            val key = parts[0].trim()
                            val value = parts[1].trim().removeSurrounding("\"").removeSurrounding("'").trim()
                            envMap[key] = value
                        }
                    }
                }
            }
        }

        logger.info("Environnement actif configuré: ${environment.uppercase()}")
    }

    /**
     * Retourne l'environnement applicatif courant : "local", "dev" ou "prod"
     */
    val environment: String
        get() = (System.getenv("APP_ENV") ?: System.getenv("ENV") ?: envMap["APP_ENV"] ?: "local").lowercase().trim()

    fun isLocal(): Boolean = environment == "local"
    fun isDevelopment(): Boolean = environment == "dev" || environment == "development"
    fun isProduction(): Boolean = environment == "prod" || environment == "production"

    fun get(key: String, default: String? = null): String? {
        val sysVal = System.getenv(key)?.takeIf { it.isNotBlank() }
        val raw = sysVal ?: envMap[key] ?: default
        return raw?.trim()?.removeSurrounding("\"")?.removeSurrounding("'")?.trim()
    }

    fun getRequired(key: String): String {
        return get(key) ?: throw IllegalStateException("Variable d'environnement manquante: $key (Environnement: $environment)")
    }

    /**
     * Retourne la liste des origines autorisées pour CORS
     */
    fun getAllowedCorsOrigins(): List<String> {
        val raw = get("CORS_ALLOWED_ORIGINS", "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split(",").map { it.trim() }.filter { it.isNotBlank() }
    }
}

