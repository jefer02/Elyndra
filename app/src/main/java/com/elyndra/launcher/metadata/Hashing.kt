package com.elyndra.launcher.metadata

import com.elyndra.launcher.data.RaHashKind
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.CRC32

/** Acceso aleatorio mínimo a un archivo (FileChannel en Android, ByteArray en tests). */
interface RandomReader {
    val size: Long
    /** Lee hasta [len] bytes en [pos]; devuelve los leídos (0 al final). */
    fun read(pos: Long, buf: ByteArray, off: Int = 0, len: Int = buf.size - off): Int
}

class ByteArrayReader(private val data: ByteArray) : RandomReader {
    override val size: Long get() = data.size.toLong()
    override fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
        if (pos >= data.size) return 0
        val n = minOf(len.toLong(), data.size - pos).toInt()
        System.arraycopy(data, pos.toInt(), buf, off, n)
        return n
    }
}

object Hashing {

    data class Digests(val crc: String, val md5: String, val sha1: String)

    fun hex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()

    /** CRC32 + MD5 + SHA-1 en una sola pasada (lo que pide ScreenScraper). */
    fun digests(input: InputStream, cancelled: () -> Boolean = { false }): Digests? {
        val crc = CRC32()
        val md5 = MessageDigest.getInstance("MD5")
        val sha1 = MessageDigest.getInstance("SHA-1")
        val buf = ByteArray(256 * 1024)
        while (true) {
            if (cancelled()) return null
            val n = input.read(buf)
            if (n < 0) break
            if (n == 0) continue
            crc.update(buf, 0, n)
            md5.update(buf, 0, n)
            sha1.update(buf, 0, n)
        }
        return Digests("%08X".format(crc.value), hex(md5.digest()), hex(sha1.digest()))
    }

    fun md5(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): String {
        val md = MessageDigest.getInstance("MD5")
        md.update(bytes, offset, length)
        return hex(md.digest())
    }
}

/**
 * Hash de RetroAchievements para ROMs de cartucho, idéntico a rcheevos
 * (src/rhash/hash_rom.c): MD5 de los primeros 64 MB del archivo, quitando
 * la cabecera cuando el formato la lleva y normalizando el orden de bytes
 * de Nintendo 64 a z64.
 */
object RaHash {

    const val MAX_BUFFER_SIZE = 64 * 1024 * 1024

    /** Bytes de cabecera que rcheevos ignora, según los primeros bytes y el tamaño hasheado. */
    fun headerSize(kind: RaHashKind, head: ByteArray, headLen: Int, hashedSize: Long): Int = when (kind) {
        RaHashKind.Nes ->
            if (hashedSize > 16 && headLen >= 4 &&
                (startsWith(head, byteArrayOf(0x4E, 0x45, 0x53, 0x1A)) || startsWith(head, byteArrayOf(0x46, 0x44, 0x53, 0x1A)))
            ) 16 else 0
        RaHashKind.Snes -> if (hashedSize - (hashedSize / 0x2000) * 0x2000 == 512L) 512 else 0
        RaHashKind.Lynx ->
            if (hashedSize > 64 && headLen >= 5 && startsWith(head, byteArrayOf(0x4C, 0x59, 0x4E, 0x58, 0x00))) 64 else 0
        RaHashKind.A7800 ->
            if (hashedSize > 128 && headLen >= 10 && String(head, 1, 9, Charsets.ISO_8859_1) == "ATARI7800") 128 else 0
        RaHashKind.Pce -> if ((hashedSize and 512L) != 0L) 512 else 0
        else -> 0
    }

    private fun startsWith(data: ByteArray, prefix: ByteArray): Boolean {
        if (data.size < prefix.size) return false
        for (i in prefix.indices) if (data[i] != prefix[i]) return false
        return true
    }

    /**
     * Hash en streaming de un cartucho de [totalSize] bytes. Solo para los
     * tipos de cartucho (Plain, Nes, Snes, Lynx, A7800, Pce, N64).
     */
    fun cartridge(kind: RaHashKind, input: InputStream, totalSize: Long): String? {
        val hashed = minOf(totalSize, MAX_BUFFER_SIZE.toLong())
        if (hashed <= 0) return null
        val md5 = MessageDigest.getInstance("MD5")
        val buf = ByteArray(64 * 1024)

        val first = readFully(input, buf, minOf(buf.size.toLong(), hashed).toInt())
        if (first <= 0) return null

        if (kind == RaHashKind.N64) {
            val swap = when (buf[0].toInt() and 0xFF) {
                0x80, 0xE8, 0x22 -> 0
                0x37 -> 16
                0x40 -> 32
                else -> return null // no es una ROM de N64
            }
            var remaining = hashed
            var n = first
            while (true) {
                swapBytes(buf, n, swap)
                md5.update(buf, 0, n)
                remaining -= n
                if (remaining <= 0) break
                n = readFully(input, buf, minOf(buf.size.toLong(), remaining).toInt())
                if (n <= 0) break
            }
            return Hashing.hex(md5.digest())
        }

        val header = headerSize(kind, buf, first, hashed)
        if (header >= hashed) return null
        md5.update(buf, header, first - header)
        var remaining = hashed - first
        while (remaining > 0) {
            val n = readFully(input, buf, minOf(buf.size.toLong(), remaining).toInt())
            if (n <= 0) break
            md5.update(buf, 0, n)
            remaining -= n
        }
        return Hashing.hex(md5.digest())
    }

    /** v64 intercambia cada par de bytes; n64 invierte cada palabra de 4 bytes. */
    private fun swapBytes(buf: ByteArray, len: Int, mode: Int) {
        when (mode) {
            16 -> {
                var i = 0
                while (i + 1 < len) {
                    val t = buf[i]; buf[i] = buf[i + 1]; buf[i + 1] = t
                    i += 2
                }
            }
            32 -> {
                var i = 0
                while (i + 3 < len) {
                    var t = buf[i]; buf[i] = buf[i + 3]; buf[i + 3] = t
                    t = buf[i + 1]; buf[i + 1] = buf[i + 2]; buf[i + 2] = t
                    i += 4
                }
            }
        }
    }

    /** Arcade: MD5 del nombre de archivo sin extensión (rc_hash_arcade). */
    fun arcade(fileName: String, parentFolder: String?): String {
        val stem = fileName.substringBeforeLast('.')
        val folder = parentFolder?.lowercase()
        val prefixed = folder != null && folder.length < 16 && folder in ARCADE_SUBSYSTEM_FOLDERS
        val text = if (prefixed) "${folder}_$stem" else stem
        return Hashing.md5(text.toByteArray(Charsets.UTF_8))
    }

    /** Carpetas de subsistema de FinalBurn Neo que rcheevos incluye en el hash. */
    private val ARCADE_SUBSYSTEM_FOLDERS = setOf(
        "nes", "fds", "sms", "msx", "ngp", "pce", "chf", "sgx", "tg16", "msx1", "neocd", "coleco",
        "sg1000", "genesis", "gamegear", "megadriv", "pcengine", "channelf", "spectrum", "megadrive",
        "supergrafx", "zxspectrum", "mastersystem", "colecovision",
    )

    /** Nintendo DS: cabecera (0x160) + código ARM9 + ARM7 + icono (0xA00), como rc_hash_nintendo_ds. */
    fun nds(reader: RandomReader): String? {
        val header = ByteArray(512)
        if (reader.read(0, header) < 512) return null
        var offset = 0L
        if (u8(header, 0) == 0x2E && u8(header, 1) == 0 && u8(header, 2) == 0 && u8(header, 3) == 0xEA &&
            u8(header, 0xB0) == 0x44 && u8(header, 0xB1) == 0x46 && u8(header, 0xB2) == 0x96 && u8(header, 0xB3) == 0
        ) {
            offset = 512 // cabecera SuperCard
            if (reader.read(offset, header) < 512) return null
        }
        val arm9Addr = le32(header, 0x20)
        val arm9Size = le32(header, 0x2C)
        val arm7Addr = le32(header, 0x30)
        val arm7Size = le32(header, 0x3C)
        val iconAddr = le32(header, 0x68)
        if (arm9Size + arm7Size > 16L * 1024 * 1024) return null

        val md5 = MessageDigest.getInstance("MD5")
        md5.update(header, 0, 0x160)
        fun appendRegion(addr: Long, size: Long) {
            val buf = ByteArray(size.toInt())
            val n = reader.read(addr + offset, buf)
            if (n < buf.size) java.util.Arrays.fill(buf, maxOf(n, 0), buf.size, 0)
            md5.update(buf)
        }
        appendRegion(arm9Addr, arm9Size)
        appendRegion(arm7Addr, arm7Size)
        appendRegion(iconAddr, 0xA00)
        return Hashing.hex(md5.digest())
    }

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF

    fun le32(b: ByteArray, i: Int): Long =
        (u8(b, i).toLong()) or (u8(b, i + 1).toLong() shl 8) or (u8(b, i + 2).toLong() shl 16) or (u8(b, i + 3).toLong() shl 24)

    fun readFully(input: InputStream, buf: ByteArray, len: Int): Int {
        var total = 0
        while (total < len) {
            val n = input.read(buf, total, len - total)
            if (n < 0) break
            total += n
        }
        return total
    }
}
