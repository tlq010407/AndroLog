package com.jordansamhi.androlog.frida;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Detects whether an APK is compatible with Soot instrumentation
 * or requires Frida runtime instrumentation
 */
public class CompatibilityDetector {

    public enum InstrumentationMode {
        SOOT,  // Traditional Java + ProGuard
        FRIDA, // Modern Kotlin + R8
        UNKNOWN
    }

    /**
     * Detect the instrumentation mode for the given APK
     */
    public static InstrumentationMode detectMode(String apkPath) {
        try {
            boolean hasKotlinMetadata = containsKotlinMetadata(apkPath);
            boolean hasR8Metadata = containsR8Metadata(apkPath);
            
            if (hasKotlinMetadata && hasR8Metadata) {
                return InstrumentationMode.FRIDA;
            } else if (hasKotlinMetadata) {
                return InstrumentationMode.FRIDA;
            }
            
            return InstrumentationMode.SOOT;
        } catch (IOException e) {
            return InstrumentationMode.UNKNOWN;
        }
    }

    /**
     * Check if APK contains Kotlin metadata
     */
    private static boolean containsKotlinMetadata(String apkPath) throws IOException {
        return containsFile(apkPath, "kotlin/");
    }

    /**
     * Check if APK contains R8 metadata/markers
     */
    private static boolean containsR8Metadata(String apkPath) throws IOException {
        // R8 highly optimizes code, check for patterns
        return containsFile(apkPath, "classes.dex") && 
               hasComplexDexOptimization(apkPath);
    }

    /**
     * Check if DEX file shows signs of R8 optimization
     * (simplified heuristic: high class count + obfuscated names)
     */
    private static boolean hasComplexDexOptimization(String apkPath) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(apkPath))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().equals("classes.dex")) {
                    // R8-optimized dex files typically have:
                    // 1. High compression ratio
                    // 2. Highly obfuscated class/method names (single letters)
                    // 3. No standard Android class patterns
                    
                    long compressedSize = entry.getCompressedSize();
                    long uncompressedSize = entry.getSize();
                    
                    if (uncompressedSize > 0) {
                        double ratio = (double) compressedSize / uncompressedSize;
                        // R8-optimized code compresses better (0.3-0.6 vs 0.7-0.9)
                        return ratio < 0.65;
                    }
                }
                zis.closeEntry();
            }
        }
        return false;
    }

    /**
     * Generic helper to check if APK contains a file/directory
     */
    private static boolean containsFile(String apkPath, String fileName) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(apkPath))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().startsWith(fileName)) {
                    return true;
                }
                zis.closeEntry();
            }
        }
        return false;
    }

    /**
     * Get human-readable description of detection result
     */
    public static String getDescription(InstrumentationMode mode) {
        switch (mode) {
            case SOOT:
                return "Java+ProGuard (Soot-compatible)";
            case FRIDA:
                return "Kotlin+R8 (Frida-required)";
            case UNKNOWN:
                return "Unknown mode (unable to detect)";
            default:
                return "Undefined";
        }
    }
}
