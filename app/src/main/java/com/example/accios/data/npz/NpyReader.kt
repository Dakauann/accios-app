package com.example.accios.data.npz

import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal NumPy `.npy/.npz` reader tailored for float32 embeddings and Unicode/string id arrays.
 */
object NpyReader {
    data class FloatMatrix(val shape: IntArray, val rows: List<FloatArray>)
    data class StringArray(val shape: IntArray, val values: List<String>)

    @Throws(IOException::class)
    fun readFloatMatrix(input: InputStream): FloatMatrix {
        val header = readHeader(input)
        if (header.fortranOrder) {
            throw IOException("Fortran-ordered arrays are not supported")
        }
        val dtype = header.descr.trim()
        if (dtype != "<f4" && dtype != "|f4" && dtype != "f4") {
            throw IOException("Unsupported dtype for float matrix: $dtype")
        }
        val total = header.shape.fold(1) { acc, dim -> acc * dim }
        val bytesPerElement = 4
        val dataLength = total * bytesPerElement
        val buffer = ByteArray(dataLength)
        readFully(input, buffer, dataLength)
        val floats = FloatArray(total)
        ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(floats)

        val rows = ArrayList<FloatArray>()
        val rowLength = if (header.shape.size >= 2) header.shape[1] else total
        val rowsCount = if (header.shape.isNotEmpty()) header.shape[0] else 1
        for (rowIndex in 0 until rowsCount) {
            val start = rowIndex * rowLength
            val row = FloatArray(rowLength)
            val copyLength = minOf(rowLength, floats.size - start)
            System.arraycopy(floats, start, row, 0, copyLength)
            rows.add(row)
        }
        return FloatMatrix(header.shape, rows)
    }

    @Throws(IOException::class)
    fun readStringArray(input: InputStream): StringArray {
        val header = readHeader(input)
        if (header.fortranOrder) {
            throw IOException("Fortran-ordered arrays are not supported")
        }
        val dtype = header.descr.trim()
        val normalized = dtype.replace(" ", "")
        val total = header.shape.fold(1) { acc, dim -> acc * dim }
        val values = ArrayList<String>(total)

        when {
            normalized.startsWith("<U") || normalized.startsWith("|U") || normalized.startsWith(">U") -> {
                val charsPerElement = normalized.substring(2).toIntOrNull()
                    ?: throw IOException("Invalid Unicode dtype: $dtype")
                val bytesPerElement = charsPerElement * 4
                val dataLength = total * bytesPerElement
                val buffer = ByteArray(dataLength)
                readFully(input, buffer, dataLength)
                val byteBuffer = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
                repeat(total) {
                    val sb = StringBuilder()
                    repeat(charsPerElement) {
                        val codePoint = byteBuffer.int
                        if (codePoint != 0) {
                            sb.append(Character.toChars(codePoint))
                        }
                    }
                    values.add(sb.toString())
                }
            }
            normalized.startsWith("|S") || normalized.startsWith("<S") || normalized.startsWith(">S") -> {
                val bytesPerElement = normalized.substring(2).toIntOrNull()
                    ?: throw IOException("Invalid string dtype: $dtype")
                val dataLength = total * bytesPerElement
                val buffer = ByteArray(dataLength)
                readFully(input, buffer, dataLength)
                repeat(total) { index ->
                    val start = index * bytesPerElement
                    val end = start + bytesPerElement
                    val slice = buffer.copyOfRange(start, end)
                    val str = slice.toString(Charsets.UTF_8).trim { it <= ' ' || it == '\u0000' }
                    values.add(str)
                }
            }
            else -> throw IOException("Unsupported dtype for string array: $dtype")
        }

        return StringArray(header.shape, values)
    }

    private fun readHeader(input: InputStream): Header {
        val magic = ByteArray(6)
        if (input.read(magic) != 6 || magic[0] != 0x93.toByte() || magic[1] != 'N'.code.toByte()) {
            throw IOException("Invalid NPY header signature")
        }
        val major = input.read()
        val minor = input.read()
        if (major == -1 || minor == -1) {
            throw IOException("Unexpected end of stream while reading NPY version")
        }
        val headerLength = when (major) {
            1 -> readLittleEndianShort(input)
            2, 3 -> readLittleEndianInt(input)
            else -> throw IOException("Unsupported NPY version: $major.$minor")
        }
        val headerBytes = ByteArray(headerLength)
        readFully(input, headerBytes, headerLength)
        val headerText = String(headerBytes, Charsets.ISO_8859_1)
        val descrMatch = Regex("'descr':\\s*'([^']+)'" ).find(headerText)
            ?: throw IOException("Descriptor not found in NPY header")
        val fortranMatch = Regex("'fortran_order':\\s*(True|False)").find(headerText)
            ?: throw IOException("fortran_order not found in NPY header")
        val shapeMatch = Regex("'shape':\\s*\\(([^\\)]*)\\)").find(headerText)
            ?: throw IOException("shape not found in NPY header")

        val descr = descrMatch.groupValues[1]
        val fortran = fortranMatch.groupValues[1].equals("True", ignoreCase = true)
        val shapeParts = shapeMatch.groupValues[1]
            .split(',')
            .mapNotNull { part ->
                val trimmed = part.trim()
                if (trimmed.isEmpty()) null else trimmed.toInt()
            }
        val shape = shapeParts.toIntArray()
        return Header(descr, fortran, shape)
    }

    private fun readFully(input: InputStream, buffer: ByteArray, length: Int) {
        var offset = 0
        while (offset < length) {
            val read = input.read(buffer, offset, length - offset)
            if (read == -1) {
                throw IOException("Unexpected end of stream")
            }
            offset += read
        }
    }

    private fun readLittleEndianShort(input: InputStream): Int {
        val b0 = input.read()
        val b1 = input.read()
        if (b0 == -1 || b1 == -1) throw IOException("Unexpected end of stream")
        return (b1 shl 8) or b0
    }

    private fun readLittleEndianInt(input: InputStream): Int {
        val b0 = input.read()
        val b1 = input.read()
        val b2 = input.read()
        val b3 = input.read()
        if (b0 == -1 || b1 == -1 || b2 == -1 || b3 == -1) throw IOException("Unexpected end of stream")
        return (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0
    }

    private data class Header(val descr: String, val fortranOrder: Boolean, val shape: IntArray)
}
