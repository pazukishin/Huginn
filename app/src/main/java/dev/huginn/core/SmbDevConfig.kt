package dev.huginn.core

import android.content.Context
import android.util.Log
import java.io.File
import java.util.Properties

/**
 * Configuración SMB para pruebas de desarrollo, leída de un fichero **privado** de la
 * app (`files/smb-dev.properties`) en vez de `intent extras`.
 *
 * Motivo: los extras de un intent aparecen en `dumpsys activity` y en logs del
 * sistema; la contraseña no debe pasar por ahí. El fichero se sube con
 * `scripts/push-smb-config.sh` (stdin, sin imprimirla) desde un fichero local
 * gitignored, y vive en el almacén privado de la app en el dispositivo.
 *
 * Formato (clave=valor):
 * ```
 * host=192.0.2.10
 * share=media
 * user=usuario
 * password=...
 * # opcionales
 * domain=WORKGROUP
 * smb1=false
 * ```
 *
 * Nunca se registra en logs ni se persiste en backups (la app es `allowBackup=false`).
 */
object SmbDevConfig {

    private const val TAG = "HuginnSmbDev"
    const val FILE_NAME = "smb-dev.properties"

    fun load(context: Context): SmbConfig? {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.isFile) return null
        return try {
            val props = Properties()
            file.inputStream().use { props.load(it) }
            val host = props.getProperty("host")?.trim().orEmpty()
            if (host.isEmpty()) {
                Log.w(TAG, "smb-dev.properties sin 'host'; se ignora")
                return null
            }
            val config = SmbConfig(
                host = host,
                share = props.getProperty("share")?.trim().orEmpty(),
                user = props.getProperty("user")?.trim().orEmpty(),
                password = props.getProperty("password").orEmpty(),
                domain = props.getProperty("domain")?.trim().orEmpty(),
                smb1 = props.getProperty("smb1")?.trim()?.toBooleanStrictOrNull() ?: false,
            )
            Log.i(TAG, "config SMB de desarrollo cargada para ${config.host}/${config.share}")
            config
        } catch (e: Exception) {
            Log.e(TAG, "no se pudo leer $FILE_NAME", e)
            null
        }
    }
}
