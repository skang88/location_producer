package com.example.location_producer

import com.example.location_producer.ble.MiBandProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MiBandProtocolTest {

    @Test
    fun parseHeartRateMeasurement_handles8Bit16BitZeroAndInvalid() {
        assertEquals(75, MiBandProtocol.parseHeartRateMeasurement(byteArrayOf(0x00, 75)))
        assertEquals(185, MiBandProtocol.parseHeartRateMeasurement(byteArrayOf(0x06, 185.toByte(), 0x00, 0x01)))
        val hr16 = MiBandProtocol.parseHeartRateMeasurement(byteArrayOf(0x01, 0x04, 0x01))
        assertNotNull(hr16)
        assertEquals(260, hr16)

        assertNull(MiBandProtocol.parseHeartRateMeasurement(byteArrayOf(0x00, 0x00)))
        assertNull(MiBandProtocol.parseHeartRateMeasurement(byteArrayOf(0x00)))
        assertNull(MiBandProtocol.parseHeartRateMeasurement(byteArrayOf()))
        assertNull(MiBandProtocol.parseHeartRateMeasurement(byteArrayOf(0x01, 0x40)))
    }

    @Test
    fun parseSpO2Measurement_handlesValidAndInvalid() {
        assertEquals(98, MiBandProtocol.parseSpO2Measurement(byteArrayOf(0x00, 0x00, 98.toByte())))
        assertEquals(100, MiBandProtocol.parseSpO2Measurement(byteArrayOf(0x00, 0x00, 100.toByte())))
        assertEquals(75, MiBandProtocol.parseSpO2Measurement(byteArrayOf(0x00, 0x00, 75.toByte())))

        assertNull(MiBandProtocol.parseSpO2Measurement(byteArrayOf(0x00, 0x00, 50.toByte())))
        assertNull(MiBandProtocol.parseSpO2Measurement(byteArrayOf(0x00, 0x00)))
        assertNull(MiBandProtocol.parseSpO2Measurement(byteArrayOf()))
    }
}
