package dev.huginn.data.smb

import java.util.Properties

/**
 * Propiedades de jcifs-ng para la conexión SMB (M3). Se aíslan aquí, sin Android,
 * para poder testear el **opt-in de SMB1** y los timeouts.
 */
object SmbJcifsProperties {

    /** Tiempo de espera de conexión/respuesta (ms): evita esperas eternas en la UI. */
    const val IO_TIMEOUT_MS = 8_000

    fun build(smb1: Boolean): Properties = Properties().apply {
        // SMB2+ por defecto; SMB1 solo si el usuario lo activa explícitamente.
        setProperty("jcifs.smb.client.minVersion", if (smb1) "SMB1" else "SMB202")
        setProperty("jcifs.smb.client.maxVersion", "SMB311")
        setProperty("jcifs.smb.client.responseTimeout", IO_TIMEOUT_MS.toString())
        setProperty("jcifs.smb.client.connTimeout", IO_TIMEOUT_MS.toString())
        setProperty("jcifs.smb.client.soTimeout", "30000")
        setProperty("jcifs.netbios.cachePolicy", "600")
        setProperty("jcifs.smb.client.dfs.disabled", "false")
        setProperty("jcifs.useSecurePortsFirst", "true")
    }
}
