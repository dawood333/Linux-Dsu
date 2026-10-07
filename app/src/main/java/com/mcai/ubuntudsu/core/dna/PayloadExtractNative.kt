package com.mcai.ubuntudsu.core.dna

/** Native fast path for independently parallelized Android OTA payload operations. */
object PayloadExtractNative {
    init {
        System.loadLibrary("payload_extract_jni")
    }

    external fun extractPartition(
        input: String,
        outputDir: String,
        partitionName: String,
        threads: Int,
        verify: Boolean,
        token: Long,
    )

    /** Packed result: high 16 bits = phase (1: extracting), low 16 bits = permille. */
    external fun getExtractProgress(token: Long): Long

    external fun cancelExtract(token: Long)
}
