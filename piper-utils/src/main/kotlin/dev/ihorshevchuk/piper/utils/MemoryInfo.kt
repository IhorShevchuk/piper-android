package dev.ihorshevchuk.piper.utils

import java.io.File

/**
 * Port of MemoryInfo (piper-objc).
 *
 * Returns the current process's resident memory in bytes, or null when it
 * cannot be determined. Mirrors Swift's Linux fallback: VmRSS is read from
 * /proc/self/status, which exists on both Linux and Android. (On Apple
 * platforms Swift uses the Mach task_info APIs instead.)
 */
object MemoryInfo {
    fun getMemoryUsage(): Long? {
        return try {
            File("/proc/self/status").useLines { lines ->
                for (line in lines) {
                    if (line.startsWith("VmRSS:")) {
                        // Format: "VmRSS:\t   12345 kB"
                        val parts = line.trim().split(Regex("\\s+"))
                        val kb = parts.getOrNull(1)?.toLongOrNull() ?: return null
                        return kb * 1024
                    }
                }
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}
