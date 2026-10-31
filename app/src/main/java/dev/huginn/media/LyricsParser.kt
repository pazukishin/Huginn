package dev.huginn.media

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Una línea de letra. `timestampMs` = -1 en letras estáticas (.txt). */
data class LyricsLine(val timestampMs: Long, val text: String)

/**
 * Letra de una canción (spec §4.5):
 * - `.lrc` con el mismo nombre junto al fichero → **sincronizada**;
 * - `.txt` → estática, sin resaltado (también se muestra);
 * - si no hay letra, la columna no se pinta.
 */
data class Lyrics(
    val lines: List<LyricsLine>,
    val synced: Boolean,
    val encoding: String,
) {
    val isEmpty: Boolean get() = lines.isEmpty()
}

/**
 * Parser de letras tolerante, sin dependencias de Android (testeable en JVM).
 *
 * Codificación: se intenta UTF-8 estricto y, si falla, Windows-1252 (muchos `.lrc`
 * antiguos). También se quita el BOM si lo hubiera.
 */
object LyricsParser {

    /** Una marca de tiempo LRC al principio de la línea: `[mm:ss]`, `[mm:ss.xx]`… */
    private val tagPattern = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")
    private val metaPattern = Regex("""^\[[a-zA-Z]+:.*]$""")

    fun decode(bytes: ByteArray): Pair<String, String> {
        val trimmed = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            bytes.copyOfRange(3, bytes.size)
        } else {
            bytes
        }
        val utf8 = runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(trimmed))
                .toString()
        }.getOrNull()
        if (utf8 != null) return utf8 to "UTF-8"
        val cp1252 = runCatching { Charset.forName("windows-1252").decode(ByteBuffer.wrap(trimmed)).toString() }
            .getOrElse { String(trimmed, Charsets.ISO_8859_1) }
        return cp1252 to "CP1252"
    }

    /**
     * Parseo con **reintento de codificación**: los `.lrc` japoneses suelen venir en
     * UTF-16 o Shift-JIS. Se prueba en orden y se prefiere la primera lectura que
     * produzca marcas de tiempo.
     */
    fun parse(bytes: ByteArray, fileName: String): Lyrics {
        val synced = fileName.endsWith(".lrc", ignoreCase = true)
        var fallback: Lyrics? = null
        for ((encoding, text) in decodeCandidates(bytes)) {
            val lyrics = parse(text, synced = synced, encoding = encoding)
            if (lyrics.isEmpty) continue
            if (lyrics.synced) return lyrics
            if (fallback == null) fallback = lyrics
        }
        return fallback ?: Lyrics(lines = emptyList(), synced = false, encoding = "UTF-8")
    }

    /** Codificaciones a probar, en orden de preferencia (BOM primero). */
    private fun decodeCandidates(bytes: ByteArray): List<Pair<String, String>> {
        val boms = mutableListOf<Pair<String, String>>()
        val body: ByteArray
        when {
            bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() -> {
                body = bytes.copyOfRange(3, bytes.size)
                boms += "UTF-8" to String(body, Charsets.UTF_8)
            }

            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> {
                body = bytes.copyOfRange(2, bytes.size)
                boms += "UTF-16LE" to String(body, Charsets.UTF_16LE)
            }

            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> {
                body = bytes.copyOfRange(2, bytes.size)
                boms += "UTF-16BE" to String(body, Charsets.UTF_16BE)
            }

            else -> body = bytes
        }

        val attempts = mutableListOf<Pair<String, String>>()
        attempts += boms
        // Sin BOM: si hay nulos alternos, casi seguro UTF-16.
        val evenNuls = body.take(2000).filterIndexed { index, _ -> index % 2 == 0 }.count { it == 0.toByte() }
        val oddNuls = body.take(2000).filterIndexed { index, _ -> index % 2 == 1 }.count { it == 0.toByte() }
        if (oddNuls > 8 && oddNuls > evenNuls) {
            attempts += "UTF-16LE" to String(body, Charsets.UTF_16LE)
        } else if (evenNuls > 8 && evenNuls > oddNuls) {
            attempts += "UTF-16BE" to String(body, Charsets.UTF_16BE)
        }
        // UTF-8 estricto.
        runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(body))
                .toString()
        }.getOrNull()?.let { attempts += "UTF-8" to it }
        // Japonesas habituales y, por último, Windows-1252.
        attempts += "Shift_JIS" to String(body, Charset.forName("Shift_JIS"))
        attempts += "CP1252" to String(body, Charset.forName("windows-1252"))
        return attempts.distinctBy { it.second.take(64) }
    }

    fun parse(text: String, synced: Boolean, encoding: String = "UTF-8"): Lyrics {
        val rawLines = text.split('\n').map { it.trimEnd('\r') }
        if (!synced) {
            return Lyrics(
                lines = rawLines.map { LyricsLine(-1, it) },
                synced = false,
                encoding = encoding,
            )
        }

        val timed = mutableListOf<LyricsLine>()
        for (line in rawLines) {
            var rest = line.trim()
            if (rest.isEmpty()) continue
            if (metaPattern.matches(rest)) continue

            // Un LRC puede llevar VARIAS marcas de tiempo al principio de la misma
            // línea (p. ej. traducciones o líneas repetidas); se quitan todas y se
            // genera una entrada por marca con el texto que queda.
            val timestamps = mutableListOf<Long>()
            while (true) {
                val match = tagPattern.find(rest) ?: break
                if (match.range.first != 0) break
                val minutes = match.groupValues[1].toLongOrNull() ?: break
                val seconds = match.groupValues[2].toLongOrNull() ?: break
                val fractionRaw = match.groupValues[3]
                val fractionMs = when (fractionRaw.length) {
                    0 -> 0L
                    1 -> fractionRaw.toLongOrNull()?.times(100) ?: 0L
                    2 -> fractionRaw.toLongOrNull()?.times(10) ?: 0L
                    else -> fractionRaw.take(3).toLongOrNull() ?: 0L
                }
                timestamps.add((minutes * 60 + seconds) * 1000 + fractionMs)
                rest = rest.removeRange(match.range)
            }
            val textLine = rest.trim()
            if (timestamps.isEmpty()) {
                // Texto sin marca de tiempo dentro de un .lrc: se conserva como estático.
                timed.add(LyricsLine(-1, textLine))
            } else {
                timestamps.forEach { timed.add(LyricsLine(it, textLine)) }
            }
        }
        val hasTimestamps = timed.any { it.timestampMs >= 0 }
        val sorted = if (hasTimestamps) timed.sortedBy { it.timestampMs } else timed
        return Lyrics(lines = sorted, synced = hasTimestamps, encoding = encoding)
    }

    /** Índice de la línea activa para una posición de reproducción (-1 si no hay). */
    fun activeLineIndex(lines: List<LyricsLine>, positionMs: Long): Int {
        if (lines.isEmpty() || lines.none { it.timestampMs >= 0 }) return -1
        var index = -1
        for (i in lines.indices) {
            if (lines[i].timestampMs in 0..positionMs) index = i else if (lines[i].timestampMs > positionMs) break
        }
        return index
    }
}
