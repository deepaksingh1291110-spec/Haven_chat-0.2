// =====================================================================
// HardwareInfo.kt  (app/src/main/java/com/haven/chat/engine/)   NEW in Phase 3
// WHAT: Reads REAL facts about the phone's processor, for the
//       "Hardware Status" card in the drawer:
//           cpuName   the chip name,           e.g. "SM6475"
//           features  the CPU instructions that make Gemma faster,
//                     e.g. "NEON, DOTPROD, I8MM"
//           cores     how many CPU cores the phone has, e.g. 8
//       Before, the card showed fixed text ("NEON", "INT8").
//
// WHY THESE FEATURES MATTER (llama.cpp uses them for speed):
//   NEON     basic ARM vector math. Every arm64 phone has it.
//   DOTPROD  fast 8-bit integer math ("dot product"). Helps q4 models.
//   I8MM     even faster 8-bit matrix math on newer chips.
//   BF16     a 16-bit number format some newer chips handle natively.
//   SVE/SVE2 a newer, wider vector system (rare on phones so far).
//   The more of these the phone has, the faster Gemma runs.
//
// WHERE THE DATA COMES FROM:
//   - the chip name:  Android's Build class (SOC_MODEL on Android 12+,
//                     otherwise Build.HARDWARE).
//   - the features:   the file /proc/cpuinfo, line "Features : fp asimd ...".
//                     In that file NEON is called "asimd", DOTPROD is
//                     "asimddp", and so on.
//   - the cores:      Runtime.availableProcessors().
//
// NEVER CRASHES: if anything cannot be read, we fall back to safe text.
//   (This app is arm64 only, and every arm64 CPU has NEON, so "NEON" is
//    always a true minimum.)
//
// USED BY: DrawerContent.kt (HardwareCard). Call HardwareInfo.read() once
//   (the card remembers the result). Reading is a few milliseconds.
// =====================================================================
package com.haven.chat.engine

import android.os.Build
import java.io.File

data class HardwareInfo(
    val cpuName: String,     // chip name for the "CPU" line
    val features: String,    // for the "Instruction Set" line
    val cores: Int           // number of CPU cores
) {
    companion object {

        /** Reads the hardware facts. Never throws. */
        fun read(): HardwareInfo {
            return HardwareInfo(
                cpuName = readCpuName(),
                features = readFeatures(),
                cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            )
        }

        // The chip name. Android 12 (API 31) and newer know the exact chip
        // model; older phones only have a short hardware code.
        private fun readCpuName(): String {
            val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else ""
            // Android writes "unknown" when it does not know.
            val name = if (soc.isBlank() || soc.equals("unknown", ignoreCase = true)) {
                Build.HARDWARE
            } else {
                soc
            }
            return if (name.isNullOrBlank() || name.equals("unknown", ignoreCase = true)) {
                "ARM64"
            } else {
                name
            }
        }

        // The CPU features that matter for Gemma, as readable text.
        private fun readFeatures(): String {
            // 1) Find the "Features" line in /proc/cpuinfo (the first one is
            //    enough: all cores of one phone report the same list, or
            //    close to it).
            val flags: Set<String> = try {
                val line = File("/proc/cpuinfo").bufferedReader().useLines { lines ->
                    lines.firstOrNull { it.startsWith("Features") }
                }
                line?.substringAfter(':')
                    ?.trim()
                    ?.split(Regex("\\s+"))
                    ?.toSet()
                    ?: emptySet()
            } catch (e: Exception) {
                emptySet()      // file not readable: continue with the minimum
            }

            // 2) Translate the file's short names into the names people use.
            val out = ArrayList<String>()
            out.add("NEON")                                  // "asimd": always on arm64
            if ("asimddp" in flags) out.add("DOTPROD")
            if ("i8mm" in flags) out.add("I8MM")
            if ("bf16" in flags) out.add("BF16")
            if ("sve2" in flags) out.add("SVE2")
            else if ("sve" in flags) out.add("SVE")
            return out.joinToString(", ")
        }
    }
}
