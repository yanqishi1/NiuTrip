package com.niutrip.app.core.route

import kotlin.math.ceil
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RouteLodPayloadTest {
    @Test
    fun `roundtrip preserves importance and mandatory flags`() {
        val importance = floatArrayOf(0f, 1.5f, 356.4f, 98_765f, Float.MAX_VALUE)
        val mandatory = booleanArrayOf(true, false, false, true, false)
        val decoded = RouteLodPayload.decode(RouteLodPayload.encode(importance, mandatory), 5)
        assertArrayEquals(importance, decoded!!.first, 0f)
        assertArrayEquals(mandatory, decoded.second)
    }

    @Test
    fun `payload layout is version count floats then bitset little endian`() {
        val payload = RouteLodPayload.encode(floatArrayOf(1f), booleanArrayOf(true))
        assertEquals(5 + 4 + 1, payload.size)
        assertEquals(RouteLodPayload.FORMAT_VERSION.toByte(), payload[0])
        // pointCount little-endian int32
        assertEquals(1, payload[1].toInt() and 0xFF)
        assertEquals(0, payload[2].toInt() and 0xFF)
        assertEquals(0, payload[3].toInt() and 0xFF)
        assertEquals(0, payload[4].toInt() and 0xFF)
        // Float32 1.0 little-endian
        assertEquals(0, payload[5].toInt() and 0xFF)
        assertEquals(0, payload[6].toInt() and 0xFF)
        assertEquals(0x80, payload[7].toInt() and 0xFF)
        assertEquals(0x3F, payload[8].toInt() and 0xFF)
        // bitset：第 0 位置 1
        assertEquals(1, payload[9].toInt() and 0xFF)
    }

    @Test
    fun `bitset packs eight points per byte lsb first`() {
        val importance = FloatArray(9)
        val mandatory = BooleanArray(9) { it % 2 == 0 }
        val payload = RouteLodPayload.encode(importance, mandatory)
        assertEquals(5 + 36 + 2, payload.size)
        assertEquals(0b0101_0101, payload[41].toInt() and 0xFF)
        assertEquals(0b0000_0001, payload[42].toInt() and 0xFF)
    }

    @Test
    fun `expected byte length formula`() {
        for (count in listOf(0, 1, 7, 8, 9, 17)) {
            val payload = RouteLodPayload.encode(FloatArray(count), BooleanArray(count))
            assertEquals(5 + 4 * count + ceil(count / 8.0).toInt(), payload.size)
        }
    }

    @Test
    fun `corrupt or mismatched payloads decode to null`() {
        val payload = RouteLodPayload.encode(floatArrayOf(1f, 2f), booleanArrayOf(false, true))
        assertNull(RouteLodPayload.decode(payload, expectedPointCount = 3))       // 点数不符
        assertNull(RouteLodPayload.decode(payload.copyOf(4), 2))                  // 截断
        assertNull(RouteLodPayload.decode(payload + 1, 2))                        // 多余字节
        val badVersion = payload.copyOf().also { it[0] = 99 }
        assertNull(RouteLodPayload.decode(badVersion, 2))                         // 版本不符
        assertNull(RouteLodPayload.decode(ByteArray(0), 0))                       // 空内容
    }

    @Test
    fun `empty payload roundtrips`() {
        val decoded = RouteLodPayload.decode(RouteLodPayload.encode(FloatArray(0), BooleanArray(0)), 0)
        assertEquals(0, decoded!!.first.size)
        assertEquals(0, decoded.second.size)
    }
}
