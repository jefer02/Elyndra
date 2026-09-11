package com.elyndra.launcher.metadata

import java.security.MessageDigest

/**
 * Imagen de CD/DVD: ISO "cocinada" (2048 bytes por sector) o BIN "en bruto"
 * (2352 bytes: sincronía + cabecera + datos). En bruto, los datos de usuario
 * empiezan en el byte 16 (Mode 1) o 24 (Mode 2 Form 1), como en rcheevos.
 */
class CdImage(private val reader: RandomReader) {

    val sectorSize: Int
    val dataOffset: Int

    init {
        val head = ByteArray(16)
        val n = reader.read(0, head)
        val raw = n == 16 && reader.size % 2352 == 0L && isSync(head)
        sectorSize = if (raw) 2352 else 2048
        dataOffset = if (!raw) 0 else if ((head[15].toInt() and 0xFF) == 1) 16 else 24
    }

    private fun isSync(h: ByteArray): Boolean {
        if (h[0].toInt() != 0 || h[11].toInt() != 0) return false
        for (i in 1..10) if ((h[i].toInt() and 0xFF) != 0xFF) return false
        return true
    }

    /** Lee hasta [len] bytes de datos de usuario del sector [lba]. */
    fun readSector(lba: Long, buf: ByteArray, len: Int = 2048): Int =
        reader.read(lba * sectorSize + dataOffset, buf, 0, minOf(len, 2048, buf.size))
}

/**
 * Hashes de RetroAchievements para PlayStation, PlayStation 2 y PSP a
 * partir de la imagen de disco (rcheevos, src/rhash/hash_disc.c).
 */
object RaDiscHash {

    /** Sector y tamaño de un archivo del disco; ruta con "\" como separador. Réplica de rc_cd_find_file_sector. */
    fun findFileSector(cd: CdImage, rawPath: String): Pair<Long, Long>? {
        var path = rawPath.removePrefix("\\")
        val buffer = ByteArray(2048)
        var sector: Long
        var numSectors = 0L
        val slash = path.lastIndexOf('\\')
        if (slash >= 0) {
            sector = findFileSector(cd, path.substring(0, slash))?.first ?: return null
            path = path.substring(slash + 1)
        } else {
            if (cd.readSector(16, buffer, 256) < 256) return null
            sector = (u8(buffer, 158) or (u8(buffer, 159) shl 8) or (u8(buffer, 160) shl 16)).toLong()
            val logicalBlockSize = u8(buffer, 128) or (u8(buffer, 129) shl 8)
            numSectors = if (logicalBlockSize == 0) 1 else RaHash.le32(buffer, 166) / logicalBlockSize
        }

        val name = path.toByteArray(Charsets.ISO_8859_1)
        if (cd.readSector(sector, buffer) <= 0) return null
        var pos = 0
        while (true) {
            if (pos >= buffer.size || buffer[pos].toInt() == 0) {
                if (numSectors > 1) {
                    numSectors--
                    sector++
                    if (cd.readSector(sector, buffer) > 0) {
                        pos = 0
                        continue
                    }
                }
                return null
            }
            val recordLength = u8(buffer, pos)
            val nameLength = if (pos + 32 < buffer.size) u8(buffer, pos + 32) else 0
            val versionMarker = pos + 33 + name.size < buffer.size && buffer[pos + 33 + name.size] == ';'.code.toByte()
            if ((nameLength == name.size || versionMarker) && regionEqualsIgnoreCase(buffer, pos + 33, name)) {
                val found = (u8(buffer, pos + 2) or (u8(buffer, pos + 3) shl 8) or (u8(buffer, pos + 4) shl 16)).toLong()
                val size = RaHash.le32(buffer, pos + 10)
                return found to size
            }
            pos += recordLength
        }
    }

    /** Añade al MD5 los [size] primeros bytes del archivo que empieza en [sector] (rc_hash_cd_file). */
    fun hashCdFile(md5: MessageDigest, cd: CdImage, startSector: Long, fileSize: Long): Boolean {
        val buffer = ByteArray(2048)
        var sector = startSector
        var numRead = cd.readSector(sector, buffer)
        if (numRead < 2048) return false
        var size = minOf(fileSize, RaHash.MAX_BUFFER_SIZE.toLong())
        if (size < numRead) numRead = size.toInt()
        while (true) {
            md5.update(buffer, 0, numRead)
            if (size <= numRead) break
            size -= numRead
            sector++
            numRead = cd.readSector(sector, buffer, if (size >= 2048) 2048 else size.toInt())
            if (numRead <= 0) break
        }
        return true
    }

    /** Busca la línea "BOOT = cdrom:\SLUS_000.01;1" de SYSTEM.CNF y devuelve el nombre del ejecutable. */
    fun bootExecutable(systemCnf: String, bootKey: String, cdromPrefix: String): String? {
        for (line in systemCnf.split('\n')) {
            if (!line.startsWith(bootKey)) continue
            var rest = line.substring(bootKey.length).trimStart { it == ' ' || it == '\t' }
            if (!rest.startsWith("=")) continue
            rest = rest.substring(1).trimStart { it == ' ' || it == '\t' }
            if (rest.startsWith(cdromPrefix)) rest = rest.substring(cdromPrefix.length)
            rest = rest.trimStart('\\')
            val end = rest.indexOfFirst { it.isWhitespace() || it == ';' }
            val name = if (end < 0) rest else rest.substring(0, end)
            return name.take(63).ifEmpty { null }
        }
        return null
    }

    private fun readSystemCnf(cd: CdImage): String? {
        val (sector, _) = findFileSector(cd, "SYSTEM.CNF") ?: return null
        val buffer = ByteArray(2047)
        val n = cd.readSector(sector, buffer, 2047)
        if (n <= 0) return null
        val text = String(buffer, 0, n, Charsets.ISO_8859_1)
        return text.substringBefore('\u0000')
    }

    /** PlayStation: MD5(nombre del ejecutable ‖ ejecutable). */
    fun psx(cd: CdImage): String? {
        var exeName = readSystemCnf(cd)?.let { bootExecutable(it, "BOOT", "cdrom:") }
        var found = exeName?.let { findFileSector(cd, it) }
        if (found == null) {
            found = findFileSector(cd, "PSX.EXE") ?: return null
            exeName = "PSX.EXE"
        }
        val (sector, dirSize) = found
        val head = ByteArray(32)
        if (cd.readSector(sector, head, 32) < 32) return null
        val size = if (String(head, 0, 7, Charsets.ISO_8859_1) == "PS-X EX") RaHash.le32(head, 28) + 2048 else dirSize
        val md5 = MessageDigest.getInstance("MD5")
        md5.update(exeName!!.toByteArray(Charsets.ISO_8859_1))
        if (!hashCdFile(md5, cd, sector, size)) return null
        return Hashing.hex(md5.digest())
    }

    /** PlayStation 2: MD5(nombre del ejecutable ‖ ejecutable), clave BOOT2 y prefijo cdrom0:. */
    fun ps2(cd: CdImage): String? {
        val exeName = readSystemCnf(cd)?.let { bootExecutable(it, "BOOT2", "cdrom0:") } ?: return null
        val (sector, size) = findFileSector(cd, exeName) ?: return null
        val md5 = MessageDigest.getInstance("MD5")
        md5.update(exeName.toByteArray(Charsets.ISO_8859_1))
        if (!hashCdFile(md5, cd, sector, size)) return null
        return Hashing.hex(md5.digest())
    }

    /** PSP (ISO): MD5(PARAM.SFO ‖ EBOOT.BIN). */
    fun psp(cd: CdImage): String? {
        val md5 = MessageDigest.getInstance("MD5")
        val (sfoSector, sfoSize) = findFileSector(cd, "PSP_GAME\\PARAM.SFO") ?: return null
        if (!hashCdFile(md5, cd, sfoSector, sfoSize)) return null
        val (ebootSector, ebootSize) = findFileSector(cd, "PSP_GAME\\SYSDIR\\EBOOT.BIN") ?: return null
        if (!hashCdFile(md5, cd, ebootSector, ebootSize)) return null
        return Hashing.hex(md5.digest())
    }

    /** PSP en formato PBP: MD5 del archivo entero (primeros 64 MB). */
    fun wholeFile(reader: RandomReader): String {
        val md5 = MessageDigest.getInstance("MD5")
        val buf = ByteArray(64 * 1024)
        var pos = 0L
        val limit = minOf(reader.size, RaHash.MAX_BUFFER_SIZE.toLong())
        while (pos < limit) {
            val n = reader.read(pos, buf, 0, minOf(buf.size.toLong(), limit - pos).toInt())
            if (n <= 0) break
            md5.update(buf, 0, n)
            pos += n
        }
        return Hashing.hex(md5.digest())
    }

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF

    private fun regionEqualsIgnoreCase(buffer: ByteArray, start: Int, name: ByteArray): Boolean {
        if (start + name.size > buffer.size) return false
        for (i in name.indices) {
            if (Character.toLowerCase(buffer[start + i].toInt().toChar()) != Character.toLowerCase(name[i].toInt().toChar())) return false
        }
        return true
    }
}
