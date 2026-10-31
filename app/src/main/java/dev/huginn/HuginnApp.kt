package dev.huginn

import android.app.Application
import dev.huginn.core.SmbDevConfig
import dev.huginn.external.ExternalLauncher
import dev.huginn.external.HuginnApplicationAccessor

/**
 * Punto de entrada de la app. Crea el contenedor manual de dependencias
 * (`AppContainer`); sin Hilt, como fija el plan.
 */
class HuginnApp : Application(), HuginnApplicationAccessor {

    lateinit var container: AppContainer
        private set

    override val externalLauncher: ExternalLauncher
        get() = container.externalLauncher

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Config SMB de desarrollo desde fichero privado (ver SmbDevConfig): evita
        // pasar contraseñas por intent extras, que aparecen en dumpsys.
        container.settings.smb = SmbDevConfig.load(this)
        // M3: si aún no hay servidores persistidos, importa la config de desarrollo
        // (contraseña cifrada en Keystore) y la deja como sesión activa.
        container.bootstrapSmb()
    }
}
