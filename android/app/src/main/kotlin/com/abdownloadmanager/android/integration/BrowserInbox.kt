package com.abdownloadmanager.android.integration

import java.io.File
import java.util.UUID

/** Private, bounded spool: a successful HTTP response means the request is on disk. */
class BrowserInbox(private val directory: File) {
    private val recent = LinkedHashMap<String, Pair<String, Long>>()

    @Synchronized
    fun accept(body: String): Pair<String, Boolean> {
        BrowserRequest.decode(body)
        val now = System.nanoTime()
        recent.entries.removeAll { now - it.value.second > 10_000_000_000L }
        recent[body]?.let { return it.first to false }
        directory.mkdirs()
        check(ids().size < 32) { "Browser inbox is full" }
        val id = UUID.randomUUID().toString()
        val temporary = File(directory, "$id.tmp")
        temporary.outputStream().use { stream ->
            stream.write(body.toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        check(temporary.renameTo(file(id))) { "Could not persist browser request" }
        recent[body] = id to now
        return id to true
    }

    @Synchronized
    fun ids(): List<String> = directory.listFiles().orEmpty()
        .filter { it.extension == "json" }.sortedBy { it.lastModified() }.map { it.nameWithoutExtension }

    @Synchronized
    fun read(id: String): BrowserRequest = BrowserRequest.decode(file(id).readText())

    @Synchronized
    fun remove(id: String) { file(id).delete() }

    private fun file(id: String): File {
        require(id.matches(Regex("[0-9a-f-]{36}")))
        return File(directory, "$id.json")
    }
}
