package com.mochyy.desktop

import com.mochyy.desktop.text.*
import com.mochyy.desktop.playback.*
import com.mochyy.desktop.library.*
import com.mochyy.desktop.account.*
import com.mochyy.desktop.ui.*
import com.mochyy.desktop.ui.screens.*
import kotlinx.coroutines.runBlocking
import java.io.File
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AccountCheck {
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    @Test
    fun chromiumKeysMatchPbkdf2() {
        // python: hashlib.pbkdf2_hmac('sha1', b'peanuts', b'saltysalt', 1, 16)
        assertEquals("fd621fe5a2b402539dfa147ca9272778", chromiumKey("peanuts").hex())
        assertEquals("d0d0ec9c7d77d43ac54187fa4818d17f", chromiumKey("").hex())
    }

    @Test
    fun chromiumCookieRoundTrip() {
        val key = chromiumKey("peanuts")
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            .apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(ByteArray(16) { ' '.code.toByte() })) }
        val hostHash = MessageDigest.getInstance("SHA-256").digest(".youtube.com".toByteArray())
        val sealed = "v10".toByteArray() + cipher.doFinal(hostHash + "abc/DEF-123".toByteArray())
        assertEquals("abc/DEF-123", decryptChromium(sealed, listOf(chromiumKey("wrong"), key), version = 24))
        assertEquals(null, decryptChromium(sealed, listOf(chromiumKey("wrong")), version = 24))
    }

    @Test
    fun pastedCookiesAndSyncIds() {
        assertEquals("SAPISID=a/b; SID=c=d", cleanCookie("Cookie: SAPISID=a/b;\n SID=c=d"))
        assertTrue(hasLogin("x=1; __Secure-3PAPISID=z") && !hasLogin("VISITOR_INFO1_LIVE=q"))
        assertEquals("123", ownDataSyncId("123"))
        assertEquals("123", ownDataSyncId("123||"))
        assertEquals("456", ownDataSyncId("123||456"))
    }

    // Needs internet and a browser signed in to YouTube on this machine; does nothing without one.
    @Test
    fun aBrowserSessionSignsIn() = runBlocking {
        val login = BrowserCookies.find().firstOrNull() ?: return@runBlocking println("no browser signed in to YouTube, skipped")
        val file = File.createTempFile("mochy-account", ".json").apply { delete() }
        try {
            val session = AccountStore(file).signIn(login.cookie).getOrThrow()
            println("signed in from ${login.label} as ${session.name}")
            assertTrue(session.name.isNotBlank() && file.isFile)
        } finally {
            file.delete()
        }
    }
}
