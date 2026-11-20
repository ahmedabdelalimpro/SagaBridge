package dev.caraalink.link

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class MirrorChannelsTest {

    @Test
    fun `CaptureConfig parse matches this exact car's live CONFIG_CAPTURE`() {
        val bb = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(1920)   // width
            .putShort(720)    // height
            .putInt(0)        // wantFps
            .putInt(2)        // wantEncoder
            .putInt(0)        // supportCodec (unused by CaptureConfig)
            .putShort(0)      // minQuality
            .putShort(0)      // maxQuality
            .putInt(4_194_304) // bitRate
        val base = bb.array()
        val extended = base + byteArrayOf(
            0, 0,
            1, // orientation
            0, // displayId
            0,
            0, // supportExtendProtocol
            0, 0, // remaining padding bytes so remaining() >= 8
        )

        val config = CaptureConfig.parse(extended)

        assertEquals(1920, config.width)
        assertEquals(720, config.height)
        assertEquals(0, config.wantFps)
        assertEquals(2, config.wantEncoder)
        assertEquals(4_194_304, config.bitRate)
        assertEquals(1, config.orientation)
        assertEquals(0, config.displayId)
        assertEquals(0, config.supportExtendProtocol)
        assertEquals(1920, config.encodeWidth)
        assertEquals(720, config.encodeHeight)
    }

    @Test
    fun `CaptureConfig without the optional tail defaults orientation and displayId to 0`() {
        val base = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(1280).putShort(720).putInt(30).putInt(1).putInt(0)
            .putShort(0).putShort(0).putInt(3_000_000)
            .array()

        val config = CaptureConfig.parse(base)

        assertEquals(1280, config.width)
        assertEquals(720, config.height)
        assertEquals(0, config.orientation)
        assertEquals(0, config.displayId)
        assertEquals(0, config.supportExtendProtocol)
    }

    @Test
    fun `MirrorDataChannel frame prefixes a little-endian 4-byte length`() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val framed = MirrorDataChannel.frame(payload)

        assertEquals(9, framed.size)
        val length = ByteBuffer.wrap(framed, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
        assertEquals(5, length)
        assertEquals(payload.toList(), framed.copyOfRange(4, 9).toList())
    }

    @Test
    fun `MirrorDataChannel NO_FRAME is a zeroed 4-byte length`() {
        assertEquals(4, MirrorDataChannel.NO_FRAME.size)
        assertEquals(0, ByteBuffer.wrap(MirrorDataChannel.NO_FRAME).order(ByteOrder.LITTLE_ENDIAN).int)
    }
}