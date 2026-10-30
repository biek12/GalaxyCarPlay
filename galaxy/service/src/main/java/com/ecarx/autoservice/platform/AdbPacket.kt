package com.ecarx.autoservice.platform

import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** ADB's little-endian 24-byte transport header, bounded before allocating payload. */
internal data class AdbPacket(val command: Int, val arg0: Int, val arg1: Int,
    val payload: ByteArray = byteArrayOf(), private val wireHeader: ByteArray? = null) {
    fun write(output: OutputStream, version: Int = VERSION_ORIGINAL) {
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(command)
            putInt(arg0)
            putInt(arg1)
            putInt(payload.size)
            putInt(if (version >= VERSION_SKIP_CHECKSUM) 0 else payload.sumOf { it.toInt() and 255 })
            putInt(command xor -1)
        }
        output.write(header.array())
        output.write(payload)
        output.flush()
    }

    /** Relays a validated startup frame without changing its negotiated checksum representation. */
    fun writeOriginalFrame(output: OutputStream) {
        val header = wireHeader ?: throw IllegalStateException("ADB frame was not read from a stream")
        output.write(header)
        output.write(payload)
        output.flush()
    }

    companion object {
        const val VERSION_ORIGINAL = 0x01000000
        const val VERSION_SKIP_CHECKSUM = 0x01000001
        const val LOCAL_MAX_DATA = 4096
        const val MAX_PEER_DATA = 1024 * 1024
        const val CNXN = 0x4e584e43
        const val STLS = 0x534c5453
        const val AUTH = 0x48545541
        const val OPEN = 0x4e45504f
        const val OKAY = 0x59414b4f
        const val WRTE = 0x45545257
        const val CLSE = 0x45534c43

        fun read(input: InputStream, maxData: Int = LOCAL_MAX_DATA, version: Int? = VERSION_ORIGINAL): AdbPacket {
            val header = ByteBuffer.wrap(readBytes(input, 24)).order(ByteOrder.LITTLE_ENDIAN)
            val command = header.int
            val a0 = header.int
            val a1 = header.int
            val length = header.int
            val checksum = header.int
            val magic = header.int
            if (command !in setOf(CNXN, STLS, AUTH, OPEN, OKAY, WRTE, CLSE) ||
                length !in 0..maxData || magic != (command xor -1)) {
                throw ControlTransportException(ControlFailure.PROTOCOL_ERROR)
            }
            if (command == CNXN && a0 !in setOf(VERSION_ORIGINAL, VERSION_SKIP_CHECKSUM)) {
                throw ControlTransportException(ControlFailure.PROTOCOL_ERROR)
            }
            val payload = readBytes(input, length)
            // CNXN advertises the version governing its own checksum; AUTH has no version field.
            val skipChecksum = when {
                version != null -> version >= VERSION_SKIP_CHECKSUM
                command == CNXN -> a0 >= VERSION_SKIP_CHECKSUM
                command == AUTH -> checksum == 0
                else -> false
            }
            if (!skipChecksum &&
                payload.sumOf { it.toInt() and 255 } != checksum) {
                throw ControlTransportException(ControlFailure.PROTOCOL_ERROR)
            }
            return AdbPacket(command, a0, a1, payload, header.array())
        }

        private fun readBytes(input: InputStream, size: Int): ByteArray = ByteArray(size).also { bytes ->
            var offset = 0
            while (offset < size) {
                val count = input.read(bytes, offset, size - offset)
                if (count <= 0) throw java.io.EOFException()
                offset += count
            }
        }
    }
}
