package com.elyndra.launcher.ports

import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * El APK de una release que lo publica dentro de un zip (Kotlin y java.util.zip,
 * sin Android: ZipApkTest). Las mismas reglas que el generador del catálogo
 * (tools/ports-catalog/portscat/zipapk.py):
 *
 *  · uno solo, o el arm64 / el que no nombra ABI si hay varios;
 *  · nada que salga del zip ("..", rutas absolutas) ni de más de [MAX_APK];
 *  · si el catálogo fija el SHA-256 del APK de dentro ([expectedSha256]), tiene que casar.
 */
object ZipApk {

    const val MAX_APK = 4L shl 30

    sealed interface Result {
        data class Ok(val apk: File, val name: String, val sha256: String) : Result
        data class Bad(val reason: String) : Result
    }

    private val ARM64 = Regex("""(?i)(arm64|aarch64|v8a)""")
    private val OTHER_ABI = Regex("""(?i)(armeabi|armv7|x86|x86_64|x64|i686)""")

    fun safeName(name: String): Boolean {
        val n = name.replace('\\', '/')
        return !n.startsWith("/") && !Regex("""^[A-Za-z]:""").containsMatchIn(n) && n.split('/').none { it == ".." }
    }

    /** El APK de dentro que se instalaría, de entre [names] y que case con [pattern]; null si no hay uno claro. */
    fun pick(names: List<String>, pattern: Regex = Regex("""(?i)\.apk$""")): String? {
        val apks = names.filter {
            it.lowercase().endsWith(".apk") && !it.endsWith("/") && safeName(it) &&
                !it.lowercase().contains("__macosx") && pattern.containsMatchIn(it)
        }
        if (apks.size <= 1) return apks.firstOrNull()
        val file = { n: String -> n.substringAfterLast('/') }
        return apks.firstOrNull { ARM64.containsMatchIn(file(it)) } ?: apks.firstOrNull { !OTHER_ABI.containsMatchIn(file(it)) }
    }

    /** Saca de [zip] el APK a [outDir]. El zip se queda; el APK sale con su SHA-256. */
    fun extract(zip: File, outDir: File, pattern: Regex = Regex("""(?i)\.apk$"""), expectedSha256: String? = null): Result {
        val zf = try {
            ZipFile(zip)
        } catch (e: java.io.IOException) {
            return Result.Bad("not a zip")
        }
        zf.use { z ->
            val names = z.entries().asSequence().map { it.name }.toList()
            val name = pick(names, pattern) ?: return Result.Bad("no single apk inside")
            val entry = z.getEntry(name) ?: return Result.Bad("no single apk inside")
            if (entry.size > MAX_APK) return Result.Bad("apk too big")
            outDir.mkdirs()
            val target = File(outDir, name.substringAfterLast('/').replace(Regex("""[^A-Za-z0-9._-]"""), "_"))
            // Nunca fuera de [outDir], ni con un nombre raro.
            if (target.canonicalFile.parentFile != outDir.canonicalFile) return Result.Bad("bad path")
            val digest = MessageDigest.getInstance("SHA-256")
            var written = 0L
            z.getInputStream(entry).use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        written += n
                        if (written > MAX_APK) {
                            out.close()
                            target.delete()
                            return Result.Bad("apk too big")
                        }
                        digest.update(buf, 0, n)
                        out.write(buf, 0, n)
                    }
                }
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            if (expectedSha256 != null && !expectedSha256.equals(sha, ignoreCase = true)) {
                target.delete()
                return Result.Bad("checksum")
            }
            return Result.Ok(target, name, sha)
        }
    }
}
