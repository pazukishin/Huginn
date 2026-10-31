package dev.huginn

import android.app.Application
import android.util.Log
import dev.huginn.core.AppSettings
import dev.huginn.core.ExternalCache
import dev.huginn.core.LocaleConfig
import dev.huginn.core.KeystoreCipher
import dev.huginn.core.MediaFactsCache
import dev.huginn.core.PlacesStore
import dev.huginn.core.SecretCipher
import dev.huginn.core.SmbConfig
import dev.huginn.core.SmbDevConfig
import dev.huginn.core.SmbServer
import dev.huginn.core.SmbServerStore
import dev.huginn.core.ThumbnailStore
import dev.huginn.core.TopPanelController
import dev.huginn.data.FileSource
import dev.huginn.data.FsPath
import dev.huginn.data.local.LocalSource
import dev.huginn.data.smb.SmbSource
import dev.huginn.external.ExternalLauncher
import dev.huginn.media.MediaEngine
import dev.huginn.media.VlcEngine
import dev.huginn.ui.explorer.ExplorerController
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Contenedor manual de dependencias (ADR 7 del plan: AppContainer + StateFlow, sin
 * Hilt en v1). App-scope: lo comparten las dos activities (explorador abajo,
 * contenido arriba) y el motor multimedia.
 */
class AppContainer(val app: Application) {

    val settings = AppSettings(app)

    val localSource = LocalSource(app)

    /** Cifrado de credenciales SMB (Keystore AES-GCM); nunca se guarda en claro. */
    val secretCipher: SecretCipher = KeystoreCipher()

    /** Servidores SMB persistidos (M3). */
    val smbServers = SmbServerStore(File(app.filesDir, SERVERS_FILE), secretCipher)

    @Volatile
    private var smbConfigCache: Pair<String, SmbConfig?>? = null

    val smbSource = SmbSource { activeSmbConfig() }

    init {
        // Se registra DESPUÉS de crear smbSource: el callback puede disparar
        // onAvailable inmediatamente (en su propio hilo) y antes daba NPE al
        // llamar a invalidateSmbSession() con el contenedor a medio construir.
        // Al recuperar la red se tira el contexto jcifs cacheado para reconectar.
        runCatching {
            app.getSystemService(android.net.ConnectivityManager::class.java)
                ?.registerDefaultNetworkCallback(
                    object : android.net.ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: android.net.Network) {
                            invalidateSmbSession()
                        }
                    },
                )
        }.onFailure { Log.w("HuginnContainer", "no se pudo observar la red", it) }
    }

    /** Una única instancia del motor multimedia para toda la app. */
    val mediaEngine: MediaEngine by lazy { VlcEngine(app) }

    val externalLauncher = ExternalLauncher(app)

    /** M5: prepara no-multimedia (SMB → caché) para abrirlo con otra app arriba. */
    val externalCache: ExternalCache by lazy { ExternalCache(app) { path -> sourceFor(path) } }

    /** Estado de la pantalla superior, compartido entre activities. */
    val topPanel = TopPanelController()

    /** Metadatos caros (M4): duración/resolución/etiquetas, persistentes en disco. */
    val mediaFacts = MediaFactsCache(File(app.filesDir, "media-facts.properties"))

    /** M6: contexto con el idioma elegido (para textos fuera de Compose). */
    @Volatile
    private var localizedCache: android.content.Context? = null

    @Volatile
    private var localizedTag: String? = null

    fun localizedContext(): android.content.Context {
        val tag = settings.language
        localizedCache?.let { if (localizedTag == tag) return it }
        val context = LocaleConfig.apply(app, tag)
        localizedCache = context
        localizedTag = tag
        return context
    }

    fun invalidateLocalizedStrings() {
        localizedCache = null
    }

    /** M5: recientes y favoritos persistentes. */
    val placesStore = PlacesStore(File(app.filesDir, "places.properties"))

    init {
        // DESPUÉS de declarar mediaFacts (los init van en orden textual): se lee en
        // segundo plano para que las consultas desde la UI sean solo memoria.
        Thread {
            mediaFacts.load()
            // Aviso para que la UI repinte chips/ficha con los datos persistidos.
            notifyFactsUpdated()
        }.apply { isDaemon = true }.start()
    }

    private val factsRevisionState = MutableStateFlow(0)

    /** Se incrementa cuando llegan metadatos nuevos (póster/dimensiones/tags). */
    val factsRevision: StateFlow<Int> = factsRevisionState

    fun notifyFactsUpdated() {
        factsRevisionState.value += 1
    }

    /** Miniaturas/previews de imagen y **pósteres de vídeo** (memoria + disco). */
    val thumbnails: ThumbnailStore by lazy {
        ThumbnailStore(
            appContext = app,
            sourceFor = { path -> sourceFor(path) },
            factsCache = mediaFacts,
            onFactsUpdated = { notifyFactsUpdated() },
        )
    }

    /** Lógica del explorador, app-scope (no atada al ciclo de vida de una activity). */
    val explorer: ExplorerController by lazy { ExplorerController(this) }

    /** Config SMB de la sesión activa (contraseña descifrada solo en memoria). */
    fun activeSmbConfig(): SmbConfig? {
        val id = settings.activeSmbServerId ?: return settings.smb
        smbConfigCache?.let { (cachedId, config) -> if (cachedId == id) return config }
        val server = smbServers.load().firstOrNull { it.id == id }
        val config = server?.let { candidate ->
            smbServers.passwordOf(candidate)?.let { password -> candidate.config(password) }
        }
        smbConfigCache = id to config
        return config
    }

    fun setActiveSmbServer(id: String?) {
        settings.activeSmbServerId = id
        invalidateSmbSession()
    }

    /** Alta/edición: con `password` null se conserva el criptograma existente. */
    fun saveSmbServer(server: SmbServer, password: String?): List<SmbServer> {
        val existing = smbServers.load().firstOrNull { it.id == server.id }
        val merged = if (password != null) {
            server.copy(encryptedPassword = secretCipher.encrypt(password))
        } else {
            smbServers.mergePreservingSecret(server, existing)
        }
        val servers = smbServers.upsert(merged)
        invalidateSmbSession()
        return servers
    }

    fun deleteSmbServer(id: String) {
        smbServers.delete(id)
        if (settings.activeSmbServerId == id) settings.activeSmbServerId = null
        invalidateSmbSession()
    }

    private fun invalidateSmbSession() {
        smbConfigCache = null
        smbSource.invalidate()
    }

    /**
     * Primera ejecución de M3: importa la config de desarrollo
     * (`smb-dev.properties`) como servidor persistido con la contraseña cifrada, para
     * no perder el flujo de pruebas existente.
     */
    fun bootstrapSmb() {
        if (smbServers.load().isNotEmpty()) return
        val dev = SmbDevConfig.load(app) ?: return
        runCatching {
            val server = SmbServer(
                id = UUID.randomUUID().toString(),
                label = "NAS (importado)",
                host = dev.host,
                share = dev.share,
                user = dev.user,
                domain = dev.domain,
                smb1 = dev.smb1,
            )
            smbServers.upsertWithPassword(server, dev.password)
            settings.activeSmbServerId = server.id
            Log.i(TAG, "config SMB de desarrollo importada como servidor ${server.host}")
        }.onFailure {
            Log.w(TAG, "no se pudo importar la config SMB de desarrollo", it)
        }
    }

    fun sourceFor(path: FsPath): FileSource = when (path) {
        is FsPath.Local -> localSource
        is FsPath.Smb -> smbSource
        FsPath.Roots -> localSource // la raíz sintética la resuelve el controlador
    }

    private companion object {
        const val TAG = "HuginnContainer"
        const val SERVERS_FILE = "smb-servers.properties"
    }
}
