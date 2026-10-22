package com.ideatic.sunwatt

import com.ideatic.sunwatt.security.KeystoreSecretCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class KeystoreSecretCipherTest {
    private val cipher = KeystoreSecretCipher()

    @Test
    fun roundTrip() {
        val plain = "https://eu5.fusionsolar.huawei.com/rest/pvms/web/kiosk/v1/station-kiosk-file?kk=demo-token ü€"
        assertEquals(plain, cipher.decrypt(cipher.encrypt(plain)))
    }

    @Test
    fun emptyTextRoundTrips() {
        assertEquals("", cipher.decrypt(cipher.encrypt("")))
    }

    @Test
    fun sameTextGivesDifferentCiphertext() {
        val first = cipher.encrypt("same text")
        val second = cipher.encrypt("same text")
        assertNotEquals(first, second)
        assertEquals("same text", cipher.decrypt(second))
    }

    @Test
    fun damagedTextThrows() {
        val encrypted = cipher.encrypt("secret")
        val damaged = encrypted.dropLast(6) + (if (encrypted.takeLast(6) == "AAAAAA") "BBBBBB" else "AAAAAA")
        assertThrows(Exception::class.java) { cipher.decrypt(damaged) }
        assertThrows(Exception::class.java) { cipher.decrypt("not base64 !!") }
    }
}
