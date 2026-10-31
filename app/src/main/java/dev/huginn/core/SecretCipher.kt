package dev.huginn.core

/**
 * Cifrado de secretos de la app (contraseñas de servidores SMB).
 *
 * Se abstrae para poder testear la persistencia en JVM con un cifrador falso: la
 * implementación real ([KeystoreCipher]) usa Android Keystore y solo existe en el
 * dispositivo.
 */
interface SecretCipher {

    /** Cifra [plaintext] y devuelve un texto seguro para persistir (base64). */
    fun encrypt(plaintext: String): String

    /**
     * Descifra [encoded]. Devuelve `null` si el criptograma es inválido o la clave ya
     * no existe (p. ej. se limpió el Keystore): el llamador debe pedir la contraseña
     * otra vez en vez de fallar.
     */
    fun decrypt(encoded: String): String?
}
