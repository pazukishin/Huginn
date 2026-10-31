package dev.huginn.core

/**
 * Preferencias de "usar siempre" para «Abrir con» (M5): un paquete por tipo MIME.
 * Formato propio, puro y testeable (`mime<TAB>paquete` por línea, valores escapados).
 */
object ExternalPrefs {

    private val UNSAFE = Regex("[\\t\\n\\r]")

    fun encode(prefs: Map<String, String>): String = prefs.entries
        .sortedBy { it.key }
        .joinToString("\n") { (mime, pkg) -> escape(mime) + "\t" + escape(pkg) }

    fun decode(text: String): Map<String, String> =
        text.lineSequence()
            .mapNotNull { line ->
                val tab = line.indexOf('\t')
                if (tab <= 0) return@mapNotNull null
                val mime = unescape(line.substring(0, tab))
                val pkg = unescape(line.substring(tab + 1))
                if (mime.isEmpty() || pkg.isEmpty()) null else mime to pkg
            }
            .toMap()

    private fun escape(value: String): String = value.replace(UNSAFE, "_").trim()

    private fun unescape(value: String): String = value.trim()
}
