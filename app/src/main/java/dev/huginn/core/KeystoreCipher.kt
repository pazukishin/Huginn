package dev.huginn.core

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Cifrado de credenciales SMB con **AES-GCM** y clave simétrica en **Android
 * Keystore** (decisión del plan: los secretos nunca se guardan en claro ni se
 * registran en logs; la app es `allowBackup=false`).
 *
 * Formato almacenado: `base64(IV || criptograma || tag)`, con IV aleatorio de 12 B
 * generado por el Keystore en cada cifrado. La clave se crea la primera vez y se
 * reutiliza; si se borra del Keystore, `decrypt` devuelve `null` y la UI pedirá la
 * contraseña de nuevo.
 */
class KeystoreCipher(private val alias: String = DEFAULT_ALIAS) : SecretCipher {

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    private fun secretKey(): SecretKey {
        (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .build(),
        )
        return generator.generateKey()
    }

    override fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    override fun decrypt(encoded: String): String? {
        if (encoded.isEmpty()) return ""
        return runCatching {
            val raw = Base64.decode(encoded, Base64.NO_WRAP)
            if (raw.size <= IV_SIZE) return@runCatching null
            val iv = raw.copyOfRange(0, IV_SIZE)
            val encrypted = raw.copyOfRange(IV_SIZE, raw.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        }.onFailure {
            // Sin detalle del contenido: el mensaje de la excepción podría incluir datos.
            Log.w(TAG, "no se pudo descifrar la credencial (clave rotada o dato corrupto)")
        }.getOrNull()
    }

    private companion object {
        const val DEFAULT_ALIAS = "huginn-smb-credentials"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BITS = 256
        const val IV_SIZE = 12
        const val TAG_BITS = 128
        const val TAG = "HuginnCrypto"
    }
}
