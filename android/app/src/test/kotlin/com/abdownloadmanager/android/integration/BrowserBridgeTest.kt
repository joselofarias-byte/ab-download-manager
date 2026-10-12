package com.abdownloadmanager.android.integration

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class BrowserBridgeTest {
    private val valid = """{"items":[{"link":"https://example.org/file.m3u8","type":"hls","headers":{"Cookie":"session=private","User-Agent":"Browser","Referer":"https://example.org/"},"downloadPage":"https://example.org/","suggestedName":"video.ts"}],"options":{"silentAdd":true,"silentStart":true}}"""

    @Test fun preservesSessionAndOptions() {
        val request = BrowserRequest.decode(valid)
        assertEquals("session=private", request.items.single().headers?.get("Cookie"))
        assertEquals("hls", request.items.single().type)
        assertEquals("video.ts", request.items.single().suggestedName)
        assertTrue(request.options.silentStart)
    }

    @Test fun validatesUrlsHeadersAndBatchBounds() {
        for (body in listOf(valid.replace("https://example.org/file.m3u8", "file:///etc/passwd"),
            valid.replace("\"hls\"", "\"dash\""), valid.replace("session=private", "bad\\r\\nInjected: yes"),
            valid.replace("video.ts", ".."),
            """{"items":[]}""", "{")) {
            assertTrue(runCatching { BrowserRequest.decode(body) }.isFailure)
        }
        assertTrue(runCatching { BrowserRequest.decode(" ".repeat(BrowserRequest.MAX_BYTES + 1)) }.isFailure)
    }

    @Test fun missingOrEmptyKeyNeverAuthorizes() {
        assertFalse(BrowserRequest.authorized("", ""))
        assertFalse(BrowserRequest.authorized("secret", null))
        assertFalse(BrowserRequest.authorized("secret", "wrong"))
        assertTrue(BrowserRequest.authorized("secret", "secret"))
    }

    @Test fun persistsAcrossRecreationAndDeduplicatesRetries() {
        val directory = Files.createTempDirectory("browser-inbox").toFile()
        try {
            val inbox = BrowserInbox(directory)
            val first = inbox.accept(valid)
            assertTrue(first.second)
            assertEquals(first.first to false, inbox.accept(valid))
            val reopened = BrowserInbox(directory)
            assertEquals(listOf(first.first), reopened.ids())
            assertEquals("hls", reopened.read(first.first).items.single().type)
            assertTrue(runCatching { reopened.read("../outside") }.isFailure)
            reopened.remove(first.first)
            assertTrue(reopened.ids().isEmpty())
        } finally { directory.deleteRecursively() }
    }

    @Test fun rejectsFullInboxWithoutAcknowledging() {
        val directory = Files.createTempDirectory("browser-inbox").toFile()
        try {
            val inbox = BrowserInbox(directory)
            repeat(32) { inbox.accept(valid.replace("video.ts", "$it.ts")) }
            assertTrue(runCatching { inbox.accept(valid) }.isFailure)
            assertEquals(32, inbox.ids().size)
        } finally { directory.deleteRecursively() }
    }

    @Test fun httpAuthValidationAndAcknowledgment() = testApplication {
        val accepted = mutableListOf<String>()
        application { browserBridgeRoutes("secret") { accepted += it } }
        assertEquals(HttpStatusCode.Unauthorized, client.post("/ping").status)
        assertEquals("pong", client.post("/ping") { header("X-Api-Key", "secret") }.bodyAsText())
        assertEquals(HttpStatusCode.Unauthorized, client.post("/add") { setBody(valid) }.status)
        assertTrue(accepted.isEmpty())
        assertEquals(HttpStatusCode.BadRequest, client.post("/add") {
            header("X-Api-Key", "secret"); setBody("{}");
        }.status)
        assertEquals("OK", client.post("/add") { header("X-Api-Key", "secret"); setBody(valid) }.bodyAsText())
        assertEquals(listOf(valid), accepted)
    }

    @Test fun httpFullInboxReturnsFailure() = testApplication {
        application { browserBridgeRoutes("secret") { error("full") } }
        assertEquals(HttpStatusCode.ServiceUnavailable, client.post("/add") {
            header("X-Api-Key", "secret"); setBody(valid)
        }.status)
    }
}
