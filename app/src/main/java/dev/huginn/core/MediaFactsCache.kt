package dev.huginn.core

import android.util.Log
import dev.huginn.data.FsEntry
import java.io.File

/**
 * Caché de [MediaFacts] (M4): LRU en memoria y **persistente en disco** para que
 * duraraciones/dimensiones/etiquetas no se vuelvan a leer por SMB tras reiniciar.
 *
 * Sin dependencias de Android en la lógica (LRU con `LinkedHashMap` y Properties),
 * para poder testearla en JVM.
 */
class MediaFactsCache(
    private val file: File,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {

    private val lock = Any()
    private val memory = object : LinkedHashMap<String, MediaFacts>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MediaFacts>?): Boolean =
            size > maxEntries
    }

    @Volatile
    private var loaded = false

    private val name = "HuginnFacts"

    /** Carga el fichero una sola vez (llamar al arrancar o antes del primer acceso). */
    fun load() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            runCatching {
                if (!file.isFile) return@runCatching
                // El codec guarda todo indexado por el hash de la clave, así que el
                // mapa en memoria se rellena directamente con esos hashes.
                MediaFactsCodec.decode(file.readText(Charsets.UTF_8)).forEach { (hash, facts) ->
                    memory[hash] = facts
                }
            }.onFailure { Log.w(name, "no se pudo cargar la caché de metadatos", it) }
            Log.i(name, "caché cargada: ${memory.size} entradas")
            loaded = true
        }
    }

    fun get(entry: FsEntry): MediaFacts? {
        load()
        val key = keyOf(entry)
        synchronized(lock) {
            memory[MediaFactsCodec.hash(key)]?.let { return it }
        }
        return null
    }

    fun put(entry: FsEntry, facts: MediaFacts) {
        if (facts.isEmpty) return
        load()
        val key = MediaFactsCodec.hash(keyOf(entry))
        synchronized(lock) {
            memory[key] = facts
            putsSincePersist++
            if (putsSincePersist >= PERSIST_EVERY) persistLocked()
        }
    }

    fun clear() {
        synchronized(lock) {
            memory.clear()
            runCatching { file.delete() }
            loaded = true
        }
    }

    private var putsSincePersist = 0

    fun flush() {
        synchronized(lock) { persistLocked() }
    }

    private fun persistLocked() {
        runCatching {
            val parent = file.parentFile
            if (parent != null && !parent.isDirectory) parent.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(MediaFactsCodec.encode(memory.toMap()), Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText(Charsets.UTF_8), Charsets.UTF_8)
                tmp.delete()
            }
            putsSincePersist = 0
        }.onFailure { Log.w(name, "no se pudo guardar la caché de metadatos", it) }
    }

    private companion object {
        const val DEFAULT_MAX_ENTRIES = 4096
        const val PERSIST_EVERY = 20
    }
}

/** Clave estable de un medio para el caché: ruta + tamaño + fecha. */
fun keyOf(entry: dev.huginn.data.FsEntry): String =
    "${entry.path}|${entry.size ?: -1}|${entry.lastModified ?: -1}"
