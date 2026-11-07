package com.example.receiverflasher

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Calendar

class SoftFile(val name: String, val file: File)

/**
 * يبني صورة FAT32 كاملة (flash.img) من قائمة ملفات، بدون روت وبدون مكتبات.
 * كل مرة نبني الصورة من الصفر: فورمات ثم كتابة الملفات في الجذر.
 */
object Fat32Image {
    private const val BPS = 512            // bytes per sector
    private const val SPC = 8              // sectors per cluster (4KB)
    private const val RESERVED = 32
    private const val CLUSTER = BPS * SPC
    private val LE = ByteOrder.LITTLE_ENDIAN

    fun build(
        target: File,
        sizeMb: Int,
        files: List<SoftFile>,
        onProgress: (Float) -> Unit = {}
    ) {
        require(sizeMb >= 512) { "الحد الأدنى 512MB حتى تكون الصورة FAT32 صحيحة" }
        val totalSectors = sizeMb.toLong() * 1024 * 1024 / BPS
        val fatSz = (((totalSectors - RESERVED) + 1024) / 1025).toInt()
        val dataStart = RESERVED + 2 * fatSz
        val clusters = ((totalSectors - dataStart) / SPC).toInt()

        val fat = ByteBuffer.allocate(fatSz * BPS).order(LE)
        fat.putInt(0, 0x0FFFFFF8); fat.putInt(4, 0x0FFFFFFF); fat.putInt(8, 0x0FFFFFFF)

        fun clusterOffset(c: Int) = (dataStart.toLong() + (c - 2).toLong() * SPC) * BPS

        val totalBytes = files.sumOf { it.file.length() }.coerceAtLeast(1)
        val needed = files.sumOf { (it.file.length() + CLUSTER - 1) / CLUSTER }
        require(needed < clusters - 3) { "حجم الصورة لا يكفي لهذه الملفات، اختر حجماً أكبر" }

        if (target.exists()) target.delete()
        RandomAccessFile(target, "rw").use { raf ->
            raf.setLength(totalSectors * BPS)

            val root = ByteBuffer.allocate(CLUSTER)
            var next = 3
            var done = 0L

            files.forEachIndexed { idx, sf ->
                val len = sf.file.length()
                require(len < 0xFFFFFFFFL) { "الملف ${sf.name} أكبر من 4GB" }
                val n = ((len + CLUSTER - 1) / CLUSTER).toInt()
                val first = if (n == 0) 0 else next

                if (n > 0) {
                    raf.seek(clusterOffset(first))
                    sf.file.inputStream().use { input ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            val r = input.read(buf)
                            if (r < 0) break
                            raf.write(buf, 0, r)
                            done += r
                            onProgress(done.toFloat() / totalBytes * 0.95f)
                        }
                    }
                    for (i in 0 until n) {
                        val c = first + i
                        fat.putInt(c * 4, if (i == n - 1) 0x0FFFFFFF else c + 1)
                    }
                    next += n
                }

                val short = shortName(sf.name, idx + 1)
                val entries = ArrayList<ByteArray>()
                if (short.lfn) entries += lfnEntries(sf.name, short.raw)
                entries += shortEntry(short.raw, short.nt, first, len)
                require(root.remaining() >= entries.size * 32) { "عدد الملفات كبير جداً" }
                entries.forEach { root.put(it) }
            }

            // الجذر
            raf.seek(clusterOffset(2)); raf.write(root.array())

            // FAT نسختين
            raf.seek(RESERVED.toLong() * BPS); raf.write(fat.array())
            raf.seek((RESERVED + fatSz).toLong() * BPS); raf.write(fat.array())

            // Boot sector + FSInfo + النسخ الاحتياطية
            val boot = bootSector(totalSectors, fatSz)
            val fsInfo = fsInfo(clusters - (next - 2), next)
            raf.seek(0); raf.write(boot)
            raf.seek(1L * BPS); raf.write(fsInfo)
            raf.seek(6L * BPS); raf.write(boot)
            raf.seek(7L * BPS); raf.write(fsInfo)
        }
        onProgress(1f)
    }

    private fun bootSector(totalSectors: Long, fatSz: Int): ByteArray {
        val b = ByteBuffer.allocate(BPS).order(LE)
        b.put(byteArrayOf(0xEB.toByte(), 0x58, 0x90.toByte()))
        b.put("MSDOS5.0".toByteArray())
        b.putShort(BPS.toShort()); b.put(SPC.toByte())
        b.putShort(RESERVED.toShort()); b.put(2)
        b.putShort(0); b.putShort(0)
        b.put(0xF8.toByte()); b.putShort(0)
        b.putShort(63); b.putShort(255)
        b.putInt(0); b.putInt(totalSectors.toInt()); b.putInt(fatSz)
        b.putShort(0); b.putShort(0)
        b.putInt(2); b.putShort(1); b.putShort(6)
        b.position(64)
        b.put(0x80.toByte()); b.put(0); b.put(0x29)
        b.putInt(System.currentTimeMillis().toInt())
        b.put("RECEIVER   ".toByteArray())
        b.put("FAT32   ".toByteArray())
        b.put(510, 0x55); b.put(511, 0xAA.toByte())
        return b.array()
    }

    private fun fsInfo(free: Int, next: Int): ByteArray {
        val b = ByteBuffer.allocate(BPS).order(LE)
        b.putInt(0, 0x41615252); b.putInt(484, 0x61417272)
        b.putInt(488, free); b.putInt(492, next)
        b.putInt(508, 0xAA550000.toInt())
        return b.array()
    }

    private class Short83(val raw: ByteArray, val nt: Int, val lfn: Boolean)

    private fun shortName(name: String, seq: Int): Short83 {
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot + 1) else ""
        val ok = Regex("[A-Za-z0-9_\\-]+")
        val simple = base.length in 1..8 && ext.length <= 3 &&
                ok.matches(base) && (ext.isEmpty() || ok.matches(ext))
        val caseOk = (base == base.uppercase() || base == base.lowercase()) &&
                (ext == ext.uppercase() || ext == ext.lowercase())
        val raw = ByteArray(11) { ' '.code.toByte() }

        if (simple && caseOk) {
            base.uppercase().forEachIndexed { i, c -> raw[i] = c.code.toByte() }
            ext.uppercase().forEachIndexed { i, c -> raw[8 + i] = c.code.toByte() }
            var nt = 0
            if (base == base.lowercase() && base.any { it.isLetter() }) nt = nt or 0x08
            if (ext == ext.lowercase() && ext.any { it.isLetter() }) nt = nt or 0x10
            return Short83(raw, nt, false)
        }
        val tail = "~$seq"
        val cleanBase = base.uppercase()
            .filter { (it.isLetterOrDigit() && it.code < 128) || it == '_' }
            .ifEmpty { "FILE" }
        val b = cleanBase.take(8 - tail.length) + tail
        val e = ext.uppercase().filter { it.isLetterOrDigit() && it.code < 128 }.take(3)
        b.forEachIndexed { i, c -> raw[i] = c.code.toByte() }
        e.forEachIndexed { i, c -> raw[8 + i] = c.code.toByte() }
        return Short83(raw, 0, true)
    }

    private fun lfnEntries(name: String, raw: ByteArray): List<ByteArray> {
        var sum = 0
        for (x in raw) sum = ((((sum and 1) shl 7) or (sum ushr 1)) + (x.toInt() and 0xFF)) and 0xFF
        val chars = name.toCharArray()
        val count = (chars.size + 12) / 13
        val offs = intArrayOf(1, 3, 5, 7, 9, 14, 16, 18, 20, 22, 24, 28, 30)
        val list = ArrayList<ByteArray>()
        for (i in count downTo 1) {
            val e = ByteArray(32)
            e[0] = (i or (if (i == count) 0x40 else 0)).toByte()
            e[11] = 0x0F
            e[13] = sum.toByte()
            for (j in 0 until 13) {
                val k = (i - 1) * 13 + j
                val ch = when {
                    k < chars.size -> chars[k].code
                    k == chars.size -> 0
                    else -> 0xFFFF
                }
                e[offs[j]] = (ch and 0xFF).toByte()
                e[offs[j] + 1] = (ch shr 8).toByte()
            }
            list += e
        }
        return list
    }

    private fun shortEntry(raw: ByteArray, nt: Int, first: Int, size: Long): ByteArray {
        val cal = Calendar.getInstance()
        val date = (((cal.get(Calendar.YEAR) - 1980) shl 9) or
                ((cal.get(Calendar.MONTH) + 1) shl 5) or cal.get(Calendar.DAY_OF_MONTH)).toShort()
        val time = ((cal.get(Calendar.HOUR_OF_DAY) shl 11) or
                (cal.get(Calendar.MINUTE) shl 5) or (cal.get(Calendar.SECOND) / 2)).toShort()
        val e = ByteBuffer.allocate(32).order(LE)
        e.put(raw)                       // 0..10
        e.put(0x20)                      // 11 attr (archive)
        e.put(nt.toByte())               // 12 NT case flags
        e.put(0)                         // 13
        e.putShort(time); e.putShort(date)   // 14..17 created
        e.putShort(date)                     // 18..19 accessed
        e.putShort((first ushr 16).toShort())// 20..21 cluster hi
        e.putShort(time); e.putShort(date)   // 22..25 modified
        e.putShort((first and 0xFFFF).toShort()) // 26..27 cluster lo
        e.putInt(size.toInt())               // 28..31
        return e.array()
    }
}
