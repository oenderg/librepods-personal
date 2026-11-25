package me.kavishdevar.librepods.core.bluetooth.aacp.packet

import me.kavishdevar.librepods.core.bluetooth.aacp.types.MessageOpcode
import me.kavishdevar.librepods.core.devices.PacketDestination

data class RenamePacket(
    val name: String,
    override val destination: PacketDestination = PacketDestination.DEVICE,
): AACPPacket {
    override val type: AACPPacketType = AACPPacketType.MESSAGE
    override val service: Byte = 0x04

    override val opcode = MessageOpcode.RENAME

    override val payload: ByteArray = run {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        byteArrayOf(
            0x01,
            (nameBytes.size and 0xFF).toByte(),
            ((nameBytes.size ushr 8) and 0xFF).toByte(),
        ) + nameBytes
    }

    companion object {
        fun create(
            name: String,
            destination: PacketDestination = PacketDestination.DEVICE
        ): RenamePacket {
            return RenamePacket(
                name = name,
                destination = destination,
            )
        }
    }
}
