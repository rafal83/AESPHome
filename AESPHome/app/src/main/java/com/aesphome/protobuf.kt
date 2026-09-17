package com.aesphome

import java.lang.System.arraycopy





public class ProtobufMessageBuilder {
    // Sealed interface to represent fields without allocating ByteArrays immediately
    private sealed interface Field {
        val number: Int
        fun getTotalSize(): Int
        fun writeTo(buffer: ByteArray, offset: Int): Int
    }

    private val fields = mutableListOf<Field>()

    fun varint(fieldNumber: Int, value: Int) = apply {
        fields.add(VarintField(fieldNumber, value))
    }

    // 64-bit varint — needed for fields declared `uint64` in api.proto (e.g. a BLE MAC
    // address packed into the low 48 bits) that don't fit a 32-bit Int.
    fun varintLong(fieldNumber: Int, value: Long) = apply {
        fields.add(VarintField64(fieldNumber, value))
    }

    fun bytes(fieldNumber: Int, data: ByteArray) = apply {
        fields.add(BytesField(fieldNumber, data))
    }
    
    fun string(fieldNumber: Int, text: String) = apply {
        fields.add(StringField(fieldNumber, text))
    }
    
    fun fixed32(fieldNumber: Int, value: Int) = apply {
        fields.add(Fixed32Field(fieldNumber, value))
    }
    
    fun float(fieldNumber: Int, value: Float) = apply {
        fields.add(Fixed32Field(fieldNumber, value.toRawBits()))
    }

    fun build(): ByteArray {
        // 1. Single exact memory allocation
        val totalSize = fields.sumOf { it.getTotalSize() }
        val result = ByteArray(totalSize)
        
        // 2. Stream all bytes directly into the target array with zero overhead
        var offset = 0
        for (field in fields) {
            offset = field.writeTo(result, offset)
        }
        return result
    }

    // --- High-Performance Field Implementations ---

    private class VarintField(override val number: Int, val value: Int) : Field {
        val tag = (number shl 3) or 0
        override fun getTotalSize() = varintSize(tag) + varintSize(value)
        override fun writeTo(buffer: ByteArray, offset: Int): Int {
            var current = writeVarint(buffer, offset, tag)
            return writeVarint(buffer, current, value)
        }
    }

    private class VarintField64(override val number: Int, val value: Long) : Field {
        val tag = (number shl 3) or 0
        override fun getTotalSize() = varintSize(tag) + varintSize64(value)
        override fun writeTo(buffer: ByteArray, offset: Int): Int {
            var current = writeVarint(buffer, offset, tag)
            return writeVarint64(buffer, current, value)
        }
    }

    private class BytesField(override val number: Int, val data: ByteArray) : Field {
        val tag = (number shl 3) or 2
        override fun getTotalSize() = varintSize(tag) + varintSize(data.size) + data.size
        override fun writeTo(buffer: ByteArray, offset: Int): Int {
            var current = writeVarint(buffer, offset, tag)
            current = writeVarint(buffer, current, data.size)
            System.arraycopy(data, 0, buffer, current, data.size)
            return current + data.size
        }
    }

    private class StringField(override val number: Int, val text: String) : Field {
        val tag = (number shl 3) or 2
        // Pre-encode string size safely using direct character estimation or string length bounds
        val encodedBytes = text.toByteArray(Charsets.UTF_8) 
        override fun getTotalSize() = varintSize(tag) + varintSize(encodedBytes.size) + encodedBytes.size
        override fun writeTo(buffer: ByteArray, offset: Int): Int {
            var current = writeVarint(buffer, offset, tag)
            current = writeVarint(buffer, current, encodedBytes.size)
            System.arraycopy(encodedBytes, 0, buffer, current, encodedBytes.size)
            return current + encodedBytes.size
        }
    }

    private class Fixed32Field(override val number: Int, val value: Int) : Field {
        val tag = (number shl 3) or 5
        override fun getTotalSize() = varintSize(tag) + 4
        override fun writeTo(buffer: ByteArray, offset: Int): Int {
            var current = writeVarint(buffer, offset, tag)
            buffer[current]     = (value and 0xFF).toByte()
            buffer[current + 1] = ((value shr 8) and 0xFF).toByte()
            buffer[current + 2] = ((value shr 16) and 0xFF).toByte()
            buffer[current + 3] = ((value shr 24) and 0xFF).toByte()
            return current + 4
        }
    }

    companion object {
        // Returns how many bytes a varint will consume without allocating an array
        private fun varintSize(value: Int): Int {
            var v = value
            var size = 0
            do {
                size++
                v = v ushr 7
            } while (v != 0)
            return size
        }

        // Writes a varint directly into an existing array at a specific index
        private fun writeVarint(buffer: ByteArray, offset: Int, value: Int): Int {
            var v = value
            var currentOffset = offset
            while (v and -0x80 != 0) {
                buffer[currentOffset++] = ((v and 0x7F) or 0x80).toByte()
                v = v ushr 7
            }
            buffer[currentOffset++] = (v and 0x7F).toByte()
            return currentOffset
        }

        // Long-valued counterparts of varintSize/writeVarint, for fields wider than 32 bits.
        private fun varintSize64(value: Long): Int {
            var v = value
            var size = 0
            do {
                size++
                v = v ushr 7
            } while (v != 0L)
            return size
        }

        private fun writeVarint64(buffer: ByteArray, offset: Int, value: Long): Int {
            var v = value
            var currentOffset = offset
            while (v and -0x80L != 0L) {
                buffer[currentOffset++] = ((v and 0x7FL) or 0x80L).toByte()
                v = v ushr 7
            }
            buffer[currentOffset++] = (v and 0x7FL).toByte()
            return currentOffset
        }
    }
}





fun encodeVarint(value: Int): ByteArray {
    var v = value

    // Fast path for small integers (0 to 127) - saves massive allocations
    if (v and -0x80 == 0) {
        return byteArrayOf(v.toByte())
    }

    // A 32-bit int varint is at most 5 bytes long
    val temp = ByteArray(5)
    var count = 0

    while (v and -0x80 != 0) {
        temp[count++] = ((v and 0x7F) or 0x80).toByte()
        v = v ushr 7
    }
    temp[count++] = (v and 0x7F).toByte()

    // Return an exactly-sized array slice
    return temp.copyOfRange(0, count)
}



/*
// potentially even than this encode varint
// Writes directly into your pre-allocated target buffer
// would require overhaul. water from a stone
fun writeVarint(buffer: ByteArray, offset: Int, value: Int): Int {
    var v = value
    var currentOffset = offset
    while (v and -0x80 != 0) {
        buffer[currentOffset++] = ((v and 0x7F) or 0x80).toByte()
        v = v ushr 7
    }
    buffer[currentOffset++] = (v and 0x7F).toByte()
    return currentOffset // Returns the new offset position for the next write
}
*/







// One shared varint decoder — callers just supply where the next byte comes from
// (a byte array, a socket, anything). Keeps the bit-shifting logic in a single place.
fun decodeVarint(nextByte: () -> Int): Int {
  var result = 0
  var shift = 0
  while (true) {
    val byte = nextByte() and 0xFF
    result = result or ((byte and 0x7F) shl shift)
    if (byte and 0x80 == 0) return result
    shift += 7
  }
}





// Same tag-length-value walk as decodeFields below, but decodes ONE specific field as a full
// 64-bit Long rather than decodeFields' 32-bit Int — needed for fields declared `uint64` in
// api.proto (e.g. the Bluetooth GATT proxy's `address`, a MAC-derived value that routinely
// exceeds 32 bits) where decodeFields' Int accumulator would silently truncate it. Scans the
// whole payload rather than stopping at the first match, so field order in the message
// doesn't matter. Returns null if the field is absent, isn't a varint, or the payload is
// malformed.
fun findVarintLongField(payload: ByteArray, fieldNumber: Int): Long? {
  var index = 0
  var result: Long? = null
  while (index < payload.size) {
    val tag = decodeVarint { payload[index++].toInt() }
    val fNum = tag shr 3
    when (tag and 7) {
      0 -> {
        var value = 0L
        var shift = 0
        while (true) {
          val byte = payload[index++].toInt() and 0xFF
          value = value or ((byte.toLong() and 0x7F) shl shift)
          if (byte and 0x80 == 0) break
          shift += 7
        }
        if (fNum == fieldNumber) result = value
      }
      5 -> index += 4
      2 -> {
        val length = decodeVarint { payload[index++].toInt() }
        index += length
      }
      else -> return result
    }
  }
  return result
}

// Decodes a payload into {field_number: value}. Varints come back as Int, everything else as ByteArray.
fun decodeFields(payload: ByteArray): Map<Int, Any> {
  val fields = HashMap<Int, Any>()
  var index = 0
  while (index < payload.size) {
    val tag = decodeVarint { payload[index++].toInt() }
    val fieldNumber = tag shr 3
    val wireType = tag and 7
    when (wireType) {
      0 -> fields[fieldNumber] = decodeVarint { payload[index++].toInt() }
      5 -> {
        fields[fieldNumber] = payload.copyOfRange(index, index + 4)
        index += 4
      }
      2 -> {
        val length = decodeVarint { payload[index++].toInt() }
        fields[fieldNumber] = payload.copyOfRange(index, index + length)
        index += length
      }
      else -> return fields
    }
  }
  return fields
}

