package dev.huginn.core

import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import dev.huginn.model.ExplorerView
import dev.huginn.model.SortKey

/**
 * Ajustes de la app. Los que deben sobrevivir al reinicio usan **SharedPreferences**
 * (síncronas y sin ceremonia); cuando llegue M3 se migrarán a DataStore junto con el
 * mapeo de botones, tal y como prevé el plan.
 *
 * Las credenciales SMB **no** se guardan aquí: viven en el fichero privado de
 * desarrollo (`SmbDevConfig`) y en M3 pasarán a Room + Keystore.
 *
 * Extras de adb (desarrollo), en `applyIntentExtras`.
 */
class AppSettings(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** M5: mapeo de botones configurable (solo overrides sobre el mapa por defecto). */
    @Volatile
    private var keyOverridesCache: Map<dev.huginn.input.InputAction, Int>? = null

    fun keyOverrides(): Map<dev.huginn.input.InputAction, Int> {
        keyOverridesCache?.let { return it }
        val decoded = KeyMapCodec.decode(prefs.getString(KEY_KEYMAP, "").orEmpty())
        keyOverridesCache = decoded
        return decoded
    }

    fun setKeyOverride(action: dev.huginn.input.InputAction, keyCode: Int) {
        val updated = keyOverrides() + (action to keyCode)
        prefs.edit { putString(KEY_KEYMAP, KeyMapCodec.encode(updated)) }
        keyOverridesCache = updated
    }

    fun clearKeyOverrides() {
        prefs.edit { remove(KEY_KEYMAP) }
        keyOverridesCache = emptyMap()
    }

    /** M6: idioma de la app (inglés por defecto; español disponible). */
    var language: String
        get() = LocaleConfig.normalize(prefs.getString(KEY_LANGUAGE, LocaleConfig.DEFAULT))
        set(value) = prefs.edit { putString(KEY_LANGUAGE, LocaleConfig.normalize(value)) }

    /** M5: app elegida con «usar siempre» por tipo MIME. */
    private var externalPrefsText: String
        get() = prefs.getString(KEY_EXTERNAL_PREFS, "").orEmpty()
        set(value) = prefs.edit { putString(KEY_EXTERNAL_PREFS, value) }

    fun preferredAppFor(mime: String): String? = ExternalPrefs.decode(externalPrefsText)[mime]

    fun rememberAppFor(mime: String, packageName: String) {
        externalPrefsText = ExternalPrefs.encode(
            ExternalPrefs.decode(externalPrefsText) + (mime to packageName),
        )
    }

    fun forgetAppFor(mime: String) {
        externalPrefsText = ExternalPrefs.encode(
            ExternalPrefs.decode(externalPrefsText) - mime,
        )
    }

    /** Servidor SMB activo (M3): id en `SmbServerStore`; null = config de desarrollo. */
    var activeSmbServerId: String?
        get() = prefs.getString(KEY_ACTIVE_SMB_SERVER, null)
        set(value) = prefs.edit { putString(KEY_ACTIVE_SMB_SERVER, value) }

    /** Árbol SAF con permiso persistente ya concedido. */
    var localTreeUri: String?
        get() = prefs.getString(KEY_LOCAL_TREE, null)
        set(value) = prefs.edit { putString(KEY_LOCAL_TREE, value) }

    var view: ExplorerView
        get() = enumOr(prefs.getString(KEY_VIEW, null), ExplorerView.LIST)
        set(value) = prefs.edit { putString(KEY_VIEW, value.name) }

    var sortKey: SortKey
        get() = enumOr(prefs.getString(KEY_SORT_KEY, null), SortKey.NAME)
        set(value) = prefs.edit { putString(KEY_SORT_KEY, value.name) }

    var sortAscending: Boolean
        get() = prefs.getBoolean(KEY_SORT_ASC, true)
        set(value) = prefs.edit { putBoolean(KEY_SORT_ASC, value) }

    /** Columnas de la Vista Galería (4 por defecto; 5 como ajuste futuro). */
    var galleryColumns: Int
        get() = prefs.getInt(KEY_GALLERY_COLUMNS, 4).coerceIn(3, 6)
        set(value) = prefs.edit().putInt(KEY_GALLERY_COLUMNS, value.coerceIn(3, 6)).apply()

    // ---- Solo en memoria (M0/M1): no se persisten ----

    /** Servidor SMB del spike (M3 lo mueve a Room + Keystore). */
    @Volatile
    var smb: SmbConfig? = null

    /** Vídeo de prueba remoto/local para validar libVLC. */
    @Volatile
    var testVideo: String? = null

    /** Dev/smoke: controles del vídeo siempre visibles (capturas deterministas). */
    @Volatile
    var controlsAlwaysVisible: Boolean = false

    fun applyIntentExtras(intent: Intent?) {
        intent ?: return
        intent.getStringExtra("local_tree")?.let { localTreeUri = it }
        intent.getStringExtra("test_video")?.let { testVideo = it }
        val host = intent.getStringExtra("smb_host")
        if (!host.isNullOrBlank()) {
            smb = SmbConfig(
                host = host,
                share = intent.getStringExtra("smb_share").orEmpty().ifBlank { "" },
                user = intent.getStringExtra("smb_user").orEmpty(),
                password = intent.getStringExtra("smb_pass").orEmpty(),
                domain = intent.getStringExtra("smb_domain").orEmpty(),
                smb1 = intent.getBooleanExtra("smb1", false),
            )
        }
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, fallback: T): T =
        name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback

    companion object {
        const val PREFS_NAME = "huginn-settings"
        const val KEY_LOCAL_TREE = "local_tree_uri"
        const val KEY_VIEW = "view"
        const val KEY_SORT_KEY = "sort_key"
        const val KEY_SORT_ASC = "sort_asc"
        const val KEY_GALLERY_COLUMNS = "gallery_columns"
        const val KEY_ACTIVE_SMB_SERVER = "active_smb_server"
        const val KEY_EXTERNAL_PREFS = "external_prefs"
        const val KEY_KEYMAP = "key_map"
        const val KEY_LANGUAGE = "language"

    }
}
