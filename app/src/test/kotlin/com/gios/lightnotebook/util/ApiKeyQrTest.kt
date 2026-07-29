package com.gios.lightnotebook.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApiKeyQrTest {

    private val key = "sk-ant-api03-" + "A".repeat(30)

    @Test
    fun bareKeyIsAccepted() {
        assertEquals(key, ApiKeyQr.keyIn(key))
    }

    @Test
    fun surroundingWhitespaceIsTrimmed() {
        assertEquals(key, ApiKeyQr.keyIn("  $key\n"))
    }

    @Test
    fun schemePrefixIsStripped() {
        assertEquals(key, ApiKeyQr.keyIn("anthropic:$key"))
        assertEquals(key, ApiKeyQr.keyIn("ANTHROPIC: $key"))
    }

    @Test
    fun anythingNotShapedLikeAKeyIsRejected() {
        assertNull(ApiKeyQr.keyIn("https://example.com"))
        assertNull(ApiKeyQr.keyIn("WIFI:S=cafe;T=WPA;P=hunter2;;"))
        assertNull(ApiKeyQr.keyIn("sk-ant-short"))
        assertNull(ApiKeyQr.keyIn("anthropic:https://example.com"))
        assertNull(ApiKeyQr.keyIn(""))
        assertNull(ApiKeyQr.keyIn("   "))
        assertNull(ApiKeyQr.keyIn(null))
    }

    @Test
    fun aKeyWithAnEmbeddedSpaceIsRejected() {
        // A wrapped key copied out of a terminal, which would authenticate as garbage.
        assertNull(ApiKeyQr.keyIn("sk-ant-api03-AAAA BBBBCCCCDDDDEEEEFFFFGGGG"))
    }
}
