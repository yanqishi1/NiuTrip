package com.niutrip.app.core.route

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * LOD 派生缓存 payload 编解码（方案 §5.1）。
 *
 * 布局（little-endian）：1 字节格式版本 + 4 字节点数 + pointCount 个 Float32 importance
 * + ceil(pointCount/8) 字节 mandatory bitset（bit i of byte i/8，LSB-first）。
 * 解码任何不一致（版本/长度/点数）都返回 null —— 派生缓存损坏只重建，不影响事实数据。
 */
object RouteLodPayload {
    const val FORMAT_VERSION: Int = 1
    private const val HEADER_BYTES = 5

    fun encode(importance: FloatArray, mandatory: BooleanArray): ByteArray {
        require(importance.size == mandatory.size) { "importance 与 mandatory 长度不一致" }
        val bitsetBytes = (importance.size + 7) / 8
        val payload = ByteArray(HEADER_BYTES + importance.size * 4 + bitsetBytes)
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(FORMAT_VERSION.toByte())
        buffer.putInt(importance.size)
        for (value in importance) buffer.putFloat(value)
        val bitsetOffset = HEADER_BYTES + importance.size * 4
        for (i in mandatory.indices) {
            if (mandatory[i]) {
                val byteIndex = bitsetOffset + i / 8
                payload[byteIndex] = (payload[byteIndex].toInt() or (1 shl (i % 8))).toByte()
            }
        }
        return payload
    }

    fun decode(payload: ByteArray, expectedPointCount: Int): Pair<FloatArray, BooleanArray>? {
        val bitsetBytes = (expectedPointCount + 7) / 8
        val expectedLength = HEADER_BYTES + expectedPointCount * 4 + bitsetBytes
        if (payload.size != expectedLength) return null
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        if (buffer.get().toInt() != FORMAT_VERSION) return null
        if (buffer.int != expectedPointCount) return null
        val importance = FloatArray(expectedPointCount)
        for (i in 0 until expectedPointCount) importance[i] = buffer.float
        val mandatory = BooleanArray(expectedPointCount)
        val bitsetOffset = HEADER_BYTES + expectedPointCount * 4
        for (i in 0 until expectedPointCount) {
            mandatory[i] = (payload[bitsetOffset + i / 8].toInt() ushr (i % 8)) and 1 == 1
        }
        return importance to mandatory
    }
}
