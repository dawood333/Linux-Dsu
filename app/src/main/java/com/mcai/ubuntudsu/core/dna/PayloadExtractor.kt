package com.mcai.ubuntudsu.core.dna

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Payload metadata and extraction façade. Listing stays in Kotlin; local extraction uses the
 * independently implemented, bounded-parallel JNI fast path. Callers retain CLI fallback.
 */
class PayloadExtractor {

    data class PartitionInfo(
        val name: String,
        val size: Long,
        val hash: String?,
    )

    data class Metadata(
        val version: String?,
        val blockSize: Int,
        val partitionCount: Int,
    )

    private var handle: Long = 0
    private var input: String = ""

    /** 打开输入（记录路径；manifest 解析由 JNI 提取任务按需缓存） */
    fun open(inputPath: String): Boolean {
        input = inputPath
        handle = 1L
        Log.d(TAG, "open(input=$inputPath)")
        return true
    }

    /** 列分区：优先纯 Kotlin 快速解析；失败返回空（本项目无 JNI/CLI payload_extract 工具，无法兜底列分区） */
    fun listPartitions(withHash: Boolean = true): List<PartitionInfo> {
        if (handle == 0L) {
            Log.e(TAG, "handle 为 0，无法列出分区")
            return emptyList()
        }
        return PayloadExtractor.fastListPartitions(input) ?: emptyList()
    }

    fun getMetadata(): Metadata? {
        if (handle == 0L) return null
        val parts = listPartitions(false)
        if (parts.isEmpty()) return null
        return Metadata(null, 4096, parts.size)
    }

    /** 提取单分区；JNI 在目标 extents 不重叠时并行执行独立 payload 操作。 */
    fun extractPartition(
        inputPath: String,
        outputDir: String,
        partitionName: String,
        threads: Int = 4,
        verify: Boolean = false,
        token: Long,
    ) {
        Log.d(TAG, "JNI 提取 $partitionName -> $outputDir (threads=$threads, token=$token)")
        PayloadExtractNative.extractPartition(inputPath, outputDir, partitionName, threads, verify, token)
    }

    /** 进度为 Pair(phase, permille)，写入实际解码字节驱动；未运行时返回 null。 */
    fun getExtractProgress(token: Long): android.util.Pair<Int, Int>? {
        val raw = PayloadExtractNative.getExtractProgress(token)
        if (raw < 0L) return null
        return android.util.Pair((raw ushr 16).toInt(), (raw and 0xFFFF).toInt())
    }

    fun cancelExtract(token: Long) {
        PayloadExtractNative.cancelExtract(token)
    }

    fun close() {
        if (handle != 0L) {
            Log.d(TAG, "close handle=$handle")
            handle = 0
        }
    }

    companion object {
        private const val TAG = "PayloadExtractor"

        /** 解析 CLI dumper 列表输出（形如 "NAME  <size>" 行） */
        @JvmStatic
        fun parseDumperList(output: String): List<PartitionInfo> {
            val out = ArrayList<PartitionInfo>()
            for (raw in output.lines()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("error")) continue
                val m = Regex("^(\\S+)\\s+(\\S+)\\s*$").find(line) ?: continue
                val name = m.groupValues[1]
                val size = m.groupValues[2].toLongOrNull() ?: 0L
                out.add(PartitionInfo(name, size, null))
            }
            return out
        }

        /**
         * 快速列分区：Java 直读 payload 头 + manifest（毫秒级出列表）。
         */
        @JvmStatic
        fun fastListPartitions(path: String): List<PartitionInfo>? {
            return try {
                java.io.RandomAccessFile(path, "r").use { raf ->
                    val head = ByteArray(20)
                    raf.readFully(head)
                    // magic "CrAU"
                    if (head[0] != 0x43.toByte() || head[1] != 0x72.toByte()
                        || head[2] != 0x41.toByte() || head[3] != 0x55.toByte()) return null
                    val first = be64(head, 4)
                    // 头布局：magic(4)+version(8)+manifest_len(8)+metadata_sig_len(4)
                    // v2+：manifest 从 24 起；v1：magic(4)+manifest_len(8)，manifest 从 12 起
                    val manifestOff: Long
                    val manifestLen: Long
                    if (first in 2..10) { manifestOff = 24; manifestLen = be64(head, 12) }
                    else { manifestOff = 12; manifestLen = first }
                    if (manifestLen < 8 || manifestLen > 512L * 1024 * 1024) return null
                    raf.seek(manifestOff)
                    val m = ByteArray(manifestLen.toInt())
                    raf.readFully(m)
                    val out = mutableListOf<PartitionInfo>()
                    val c = Cursor(m, 0, m.size)
                    while (c.i < c.end) {
                        val tag = readVarint(c) ?: break
                        val field = (tag ushr 3).toInt()
                        val wt = (tag and 7).toInt()
                        if (wt == 2) {
                            val len = readVarint(c) ?: break
                            val l = len.toInt()
                            if (l < 0 || c.i + l > c.end) break
                            if (field == 13) parsePartition(m, c.i, c.i + l)?.let { out.add(it) }
                            c.i += l
                        } else if (!skipField(c, wt)) break
                    }
                    if (out.isEmpty()) null else out
                }
            } catch (e: Exception) {
                Log.e(TAG, "fastListPartitions failed", e)
                null
            }
        }

        /**
         * 增量包检测：任一 PartitionUpdate 含 old_partition_info(field 6) 即为增量（delta）payload。
         */
        @JvmStatic
        fun fastIsIncremental(path: String): Boolean {
            return try {
                java.io.RandomAccessFile(path, "r").use { raf ->
                    val head = ByteArray(20)
                    raf.readFully(head)
                    if (head[0] != 0x43.toByte() || head[1] != 0x72.toByte()
                        || head[2] != 0x41.toByte() || head[3] != 0x55.toByte()) return false
                    val first = be64(head, 4)
                    val manifestOff: Long
                    val manifestLen: Long
                    if (first in 2..10) { manifestOff = 24; manifestLen = be64(head, 12) }
                    else { manifestOff = 12; manifestLen = first }
                    if (manifestLen < 8 || manifestLen > 512L * 1024 * 1024) return false
                    raf.seek(manifestOff)
                    val m = ByteArray(manifestLen.toInt())
                    raf.readFully(m)
                    val c = Cursor(m, 0, m.size)
                    while (c.i < c.end) {
                        val tag = readVarint(c) ?: break
                        val field = (tag ushr 3).toInt()
                        val wt = (tag and 7).toInt()
                        if (wt == 2) {
                            val len = readVarint(c) ?: break
                            val l = len.toInt()
                            if (l < 0 || c.i + l > c.end) break
                            if (field == 13 && hasOldInfo(m, c.i, c.i + l)) return true
                            c.i += l
                        } else if (!skipField(c, wt)) break
                    }
                    false
                }
            } catch (e: Exception) {
                false
            }
        }

        private fun hasOldInfo(b: ByteArray, from: Int, to: Int): Boolean {
            val c = Cursor(b, from, to)
            while (c.i < c.end) {
                val tag = readVarint(c) ?: break
                val field = (tag ushr 3).toInt()
                val wt = (tag and 7).toInt()
                if (wt == 2) {
                    val len = readVarint(c) ?: break
                    val l = len.toInt()
                    if (l < 0 || c.i + l > c.end) break
                    if (field == 6) return true
                    c.i += l
                } else if (!skipField(c, wt)) break
            }
            return false
        }

        /** PartitionUpdate{ 1:name(str), 7:new_partition_info{ 1:size(varint) } } */
        private fun parsePartition(b: ByteArray, from: Int, to: Int): PartitionInfo? {
            val c = Cursor(b, from, to)
            var name: String? = null
            var size = 0L
            while (c.i < c.end) {
                val tag = readVarint(c) ?: break
                val field = (tag ushr 3).toInt()
                val wt = (tag and 7).toInt()
                if (wt == 2) {
                    val len = readVarint(c) ?: break
                    val l = len.toInt()
                    if (l < 0 || c.i + l > c.end) break
                    if (field == 1 && name == null)
                        name = String(b, c.i, l, Charsets.UTF_8)
                    else if (field == 7 && size == 0L)
                        size = parseInfoSize(b, c.i, c.i + l)
                    c.i += l
                } else if (!skipField(c, wt)) break
            }
            return name?.let { PartitionInfo(it, size, null) }
        }

        /** PartitionInfo{ 1:size(varint) } */
        private fun parseInfoSize(b: ByteArray, from: Int, to: Int): Long {
            val c = Cursor(b, from, to)
            while (c.i < c.end) {
                val tag = readVarint(c) ?: break
                val field = (tag ushr 3).toInt()
                val wt = (tag and 7).toInt()
                if (wt == 0) {
                    val v = readVarint(c) ?: break
                    if (field == 1) return v
                } else if (!skipField(c, wt)) break
            }
            return 0L
        }

        private class Cursor(val b: ByteArray, var i: Int, val end: Int)

        private fun readVarint(c: Cursor): Long? {
            var shift = 0
            var v = 0L
            while (c.i < c.end && shift < 64) {
                val byte = c.b[c.i++].toInt() and 0xFF
                v = v or ((byte and 0x7F).toLong() shl shift)
                if (byte and 0x80 == 0) return v
                shift += 7
            }
            return null
        }

        private fun skipField(c: Cursor, wt: Int): Boolean {
            return when (wt) {
                0 -> readVarint(c) != null
                1 -> { c.i += 8; c.i <= c.end }
                5 -> { c.i += 4; c.i <= c.end }
                else -> false
            }
        }

        private fun be64(b: ByteArray, off: Int): Long {
            var v = 0L
            for (k in 0 until 8) v = (v shl 8) or (b[off + k].toLong() and 0xFF)
            return v
        }
    }
}
