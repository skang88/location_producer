package com.example.location_producer

import com.example.location_producer.ble.MiBandProtocol
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MiBandProtocolTest {

    private fun hexToBytes(hex: String): ByteArray {
        return ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    @Test
    fun parseAuthKeyHex_handlesValidFormats() {
        val raw = "000102030405060708090a0b0c0d0e0f"
        val expected = hexToBytes(raw)

        assertArrayEquals(expected, MiBandProtocol.parseAuthKeyHex(raw))
        assertArrayEquals(expected, MiBandProtocol.parseAuthKeyHex("0x$raw"))
        assertArrayEquals(expected, MiBandProtocol.parseAuthKeyHex(" 0X0001:0203:0405:0607:0809:0A0B:0C0D:0E0F "))
        assertTrue(MiBandProtocol.isValidAuthKeyInput(""))
        assertTrue(MiBandProtocol.isValidAuthKeyInput("0x$raw"))
    }

    @Test
    fun parseAuthKeyHex_rejectsInvalidFormats() {
        assertNull(MiBandProtocol.parseAuthKeyHex("001122"))
        assertNull(MiBandProtocol.parseAuthKeyHex("zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz"))
        assertNull(MiBandProtocol.parseAuthKeyHex("000102030405060708090a0b0c0d0e0f00"))

        assertFalse(MiBandProtocol.isValidAuthKeyInput("1234"))
        assertFalse(MiBandProtocol.isValidAuthKeyInput("0x1234"))
    }

    @Test
    fun encryptChallenge_matchesNistAes128EcbVector() {
        val key = hexToBytes("000102030405060708090a0b0c0d0e0f")
        val plaintext = hexToBytes("00112233445566778899aabbccddeeff")
        val expectedCiphertext = hexToBytes("69c4e0d86a7b0430d8cdb78070b4c55a")

        val actual = MiBandProtocol.encryptChallenge(key, plaintext)
        assertArrayEquals(expectedCiphertext, actual)

        val response = MiBandProtocol.buildEncryptedAuthResponse(key, plaintext, 0x08)
        assertArrayEquals(byteArrayOf(0x03, 0x08) + expectedCiphertext, response)

        val cryptResponse = MiBandProtocol.buildEncryptedAuthResponse(
            key,
            plaintext,
            authFlag = 0x00,
            useCryptFlag = true
        )
        assertArrayEquals(byteArrayOf(0x83.toByte(), 0x00) + expectedCiphertext, cryptResponse)
    }

    @Test
    fun buildRequestRandomChallenge_buildsExpectedVariants() {
        assertArrayEquals(
            byteArrayOf(0x02, 0x00),
            MiBandProtocol.buildRequestRandomChallenge()
        )
        assertArrayEquals(
            byteArrayOf(0x02, 0x08),
            MiBandProtocol.buildRequestRandomChallenge(authFlag = 0x08)
        )
        assertArrayEquals(
            byteArrayOf(0x02, 0x00, 0x02),
            MiBandProtocol.buildRequestRandomChallenge(includeProtocolVersion = true)
        )
        assertArrayEquals(
            byteArrayOf(0x82.toByte(), 0x00, 0x02),
            MiBandProtocol.buildRequestRandomChallenge(useCryptFlag = true, includeProtocolVersion = true)
        )
    }

    @Test
    fun parseAuthNotification_parsesChallengeSuccessFailureAndUnknown() {
        val random16 = hexToBytes("11223344556677889900aabbccddeeff")
        val challengePacket = byteArrayOf(0x10, 0x02, 0x01) + random16
        val parsedChallenge = MiBandProtocol.parseAuthNotification(challengePacket)
        assertTrue(parsedChallenge is MiBandProtocol.AuthNotification.Challenge)
        assertArrayEquals(random16, (parsedChallenge as MiBandProtocol.AuthNotification.Challenge).random16)
        assertEquals(0x02.toByte(), parsedChallenge.command)

        val cryptChallengePacket = byteArrayOf(0x10, 0x82.toByte(), 0x01) + random16
        val parsedCryptChallenge = MiBandProtocol.parseAuthNotification(cryptChallengePacket)
        assertTrue(parsedCryptChallenge is MiBandProtocol.AuthNotification.Challenge)
        assertEquals(0x82.toByte(), (parsedCryptChallenge as MiBandProtocol.AuthNotification.Challenge).command)

        assertEquals(
            MiBandProtocol.AuthNotification.AuthSuccess,
            MiBandProtocol.parseAuthNotification(byteArrayOf(0x10, 0x03, 0x01))
        )
        assertEquals(
            MiBandProtocol.AuthNotification.AuthSuccess,
            MiBandProtocol.parseAuthNotification(byteArrayOf(0x10, 0x83.toByte(), 0x01))
        )

        val failure = MiBandProtocol.parseAuthNotification(byteArrayOf(0x10, 0x03, 0x04))
        assertEquals(MiBandProtocol.AuthNotification.AuthFailure(0x03, 0x04), failure)

        val unknown = MiBandProtocol.parseAuthNotification(byteArrayOf(0x01, 0x02))
        assertTrue(unknown is MiBandProtocol.AuthNotification.Unknown)
    }

    @Test
    fun parseHeartRateMeasurement_handles8Bit16BitZeroAndInvalid() {
        assertEquals(75, MiBandProtocol.parseHeartRateMeasurement(byteArrayOf(0x00, 75)))
        assertEquals(185, MiBandProtocol.parseHeartRateMeasurement(byteArrayOf(0x06, 185.toByte(), 0x00, 0x01)))
        // 16-bit format: 0x0104 little-endian = 260
        val hr16 = MiBandProtocol.parseHeartRateMeasurement(byteArrayOf(0x01, 0x04, 0x01))
        assertNotNull(hr16)
        assertEquals(260, hr16)

        assertNull(MiBandProtocol.parseHeartRateMeasurement(byteArrayOf(0x00, 0x00)))
        assertNull(MiBandProtocol.parseHeartRateMeasurement(byteArrayOf(0x00)))
        assertNull(MiBandProtocol.parseHeartRateMeasurement(byteArrayOf()))
        assertNull(MiBandProtocol.parseHeartRateMeasurement(byteArrayOf(0x01, 0x40)))
    }
}
