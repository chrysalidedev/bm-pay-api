package com.buildmain.utils

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Utilitaires cryptographiques pour la sécurité du SaaS Build-Main :
 *  - Hachage de mot de passe fort via PBKDF2WithHmacSHA256 (recommandé OWASP/NIST)
 *  - Génération et validation de jetons de session signés HMAC-SHA256
 */
object SecurityUtils {
    private const val ITERATIONS = 65536
    private const val KEY_LENGTH = 256
    private const val ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val HMAC_ALGO = "HmacSHA256"

    private val secureRandom = SecureRandom()

    /**
     * Hache un mot de passe en clair avec un sel cryptographique aléatoire de 16 octets.
     * Format retourné : "saltHex:hashHex"
     */
    fun hashPassword(password: String): String {
        val salt = ByteArray(16)
        secureRandom.nextBytes(salt)

        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_LENGTH)
        val factory = SecretKeyFactory.getInstance(ALGORITHM)
        val hash = factory.generateSecret(spec).encoded

        val saltHex = salt.joinToString("") { "%02x".format(it) }
        val hashHex = hash.joinToString("") { "%02x".format(it) }
        return "$saltHex:$hashHex"
    }

    /**
     * Vérifie la concordance entre un mot de passe en clair et un condensé haché stocké.
     */
    fun verifyPassword(password: String, storedHash: String): Boolean {
        if (!storedHash.contains(":")) return false
        val parts = storedHash.split(":")
        if (parts.size != 2) return false

        val saltHex = parts[0]
        val expectedHashHex = parts[1]

        val salt = saltHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_LENGTH)
        val factory = SecretKeyFactory.getInstance(ALGORITHM)
        val hash = factory.generateSecret(spec).encoded
        val actualHashHex = hash.joinToString("") { "%02x".format(it) }

        return slowEquals(actualHashHex.toByteArray(), expectedHashHex.toByteArray())
    }

    /**
     * Comparaison à temps constant pour prévenir les attaques temporelles (timing attacks).
     */
    private fun slowEquals(a: ByteArray, b: ByteArray): Boolean {
        var diff = a.size xor b.size
        for (i in 0 until minOf(a.size, b.size)) {
            diff = diff or (a[i].toInt() xor b[i].toInt())
        }
        return diff == 0
    }

    /**
     * Génère un jeton d'authentification signé HMAC-SHA256 :
     * Format : base64(churchId:email:timestamp:expiresAt).base64(signature)
     */
    fun generateAuthToken(
        churchId: String,
        email: String,
        secretKey: String,
        validityDays: Long = 30
    ): String {
        val now = System.currentTimeMillis()
        val expiresAt = now + (validityDays * 24 * 60 * 60 * 1000)
        val payload = "$churchId|$email|$now|$expiresAt"
        val payloadEncoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray(Charsets.UTF_8))

        val signature = computeHmac(payloadEncoded, secretKey)
        return "$payloadEncoded.$signature"
    }

    /**
     * Valide un jeton d'authentification signé.
     * Retourne Pair(churchId, email) si le jeton est authentique et non expiré, sinon null.
     */
    fun verifyAuthToken(token: String, secretKey: String): Pair<String, String>? {
        val parts = token.split(".")
        if (parts.size != 2) return null

        val payloadEncoded = parts[0]
        val signature = parts[1]

        val expectedSig = computeHmac(payloadEncoded, secretKey)
        if (!slowEquals(signature.toByteArray(), expectedSig.toByteArray())) {
            return null
        }

        return try {
            val payload = String(Base64.getUrlDecoder().decode(payloadEncoded), Charsets.UTF_8)
            val fields = payload.split("|")
            if (fields.size != 4) return null

            val churchId = fields[0]
            val email = fields[1]
            val expiresAt = fields[3].toLongOrNull() ?: return null

            if (System.currentTimeMillis() > expiresAt) {
                return null // Jeton expiré
            }

            Pair(churchId, email)
        } catch (e: Exception) {
            null
        }
    }

    private fun computeHmac(data: String, key: String): String {
        val sha256Hmac = Mac.getInstance(HMAC_ALGO)
        val secretKeySpec = SecretKeySpec(key.toByteArray(Charsets.UTF_8), HMAC_ALGO)
        sha256Hmac.init(secretKeySpec)
        val rawHmac = sha256Hmac.doFinal(data.toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(rawHmac)
    }
}
