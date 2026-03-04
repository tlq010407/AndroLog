package com.jordansamhi.androlog;

import com.jordansamhi.androspecter.printers.Writer;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/**
 * Handles the preparation of APK files including signing and alignment.
 * This class utilizes external tools specified in a configuration file for APK processing.
 */
public class ApkPreparator {

    private final String apksignerPath;
    private final String zipalignPath;
    private final String apkPath;

    /**
     * Initializes the ApkPreparator with the path of the APK to be processed.
     * Loads the paths for the apksigner and zipalign tools from a configuration file.
     *
     * @param apkPath The file path of the APK to be prepared.
     */
    public ApkPreparator(String apkPath) {
        this.apkPath = apkPath;
        Properties props = new Properties();
        InputStream input = getClass().getClassLoader().getResourceAsStream("config.properties");
        try {
            props.load(input);
        } catch (Exception e) {
            Writer.v().perror("Problem with config file.");
        }

        String resolvedApkSignerPath = props.getProperty("apksignerPath");
        String resolvedZipalignPath = props.getProperty("zipalignPath");

        if (resolvedApkSignerPath == null || resolvedApkSignerPath.isBlank()) {
            resolvedApkSignerPath = props.getProperty("apksigner");
        }
        if (resolvedZipalignPath == null || resolvedZipalignPath.isBlank()) {
            resolvedZipalignPath = props.getProperty("zipalign");
        }

        this.apksignerPath = resolvedApkSignerPath;
        this.zipalignPath = resolvedZipalignPath;
    }

    /**
     * Prepares the APK by aligning, normalizing manifest split attributes, and signing.
     * The original APK is replaced by the processed version at each step.
     */
    public void prepareApk() {
        String signedApkPath = apkPath.replace(".apk", "_signed.apk");
        String alignedApkPath = apkPath.replace(".apk", "_aligned.apk");

        alignApk(alignedApkPath);
        replaceOriginalApk(alignedApkPath);

        removeSplitAttributes();

        signApk(signedApkPath);
        replaceOriginalApk(signedApkPath);
    }

    /**
     * Removes split-related attributes that can make a re-packed single APK be interpreted
     * as a base split package and fail with INSTALL_FAILED_MISSING_SPLIT.
     */
    private void removeSplitAttributes() {
        String username = System.getProperty("user.name");
        Path tmpDir = Paths.get(String.format("/tmp/%s/androlog_manifest_fix_%d", username, System.nanoTime()));
        String rebuiltApkPath = apkPath.replace(".apk", "_nosplit.apk");

        try {
            Files.createDirectories(tmpDir);

            int decodeExit = executeCommand(String.format("apktool d -f %s -o %s", apkPath, tmpDir));
            if (decodeExit != 0) {
                Writer.v().perror("Could not decode APK to remove split attributes.");
                return;
            }

            Path manifestPath = tmpDir.resolve("AndroidManifest.xml");
            if (!Files.exists(manifestPath)) {
                Writer.v().perror("AndroidManifest.xml not found in decoded APK.");
                return;
            }

            String manifest = Files.readString(manifestPath);
            String updatedManifest = manifest
                    .replaceAll("\\sandroid:requiredSplitTypes=\"[^\"]*\"", "")
                    .replaceAll("\\sandroid:splitTypes=\"[^\"]*\"", "");

            if (!updatedManifest.equals(manifest)) {
                Files.writeString(manifestPath, updatedManifest);
                int buildExit = executeCommand(String.format("apktool b %s -o %s", tmpDir, rebuiltApkPath));
                if (buildExit != 0) {
                    Writer.v().perror("Could not rebuild APK after removing split attributes.");
                    return;
                }
                replaceOriginalApk(rebuiltApkPath);
            }
        } catch (Exception e) {
            Writer.v().perror("Problem while removing split attributes: " + e.getMessage());
        } finally {
            try {
                if (Files.exists(tmpDir)) {
                    FileUtils.deleteDirectory(tmpDir.toFile());
                }
                Path rebuiltApk = Paths.get(rebuiltApkPath);
                if (Files.exists(rebuiltApk)) {
                    Files.delete(rebuiltApk);
                }
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Signs the APK using the apksigner tool.
     * The signed APK is saved to the specified output path.
     *
     * @param outputApk The file path where the signed APK will be saved.
     */
    private void signApk(String outputApk) {
        String keystorePath = extractKeystore();
        String command = String.format(
                "%s sign --ks %s --ks-pass pass:android --in %s --out %s --ks-key-alias android",
                apksignerPath, keystorePath, apkPath, outputApk);
        executeCommand(command);
        deleteIdsigFile(outputApk);
    }

    /**
     * Deletes the .idsig file associated with the specified APK.
     * This method checks for the existence of a .idsig file, which shares the same base name as the APK,
     * and attempts to delete it. If the deletion process encounters any IOException, an error message
     * is logged
     *
     * @param apkPath The file path of the APK whose .idsig file is to be deleted.
     */
    private void deleteIdsigFile(String apkPath) {
        try {
            Path idsigPath = Paths.get(apkPath.replace(".apk", ".apk.idsig"));
            if (Files.exists(idsigPath)) {
                Files.delete(idsigPath);
            }
        } catch (IOException e) {
            Writer.v().perror("Problem with deleting the .idsig file: " + e.getMessage());
        }
    }


    /**
     * Aligns the APK using the zipalign tool.
     * The aligned APK is saved to the specified output path.
     *
     * @param outputApk The file path where the aligned APK will be saved.
     */
    private void alignApk(String outputApk) {
        String command = String.format("%s -v 4 %s %s", zipalignPath, apkPath, outputApk);
        executeCommand(command);
    }

    /**
     * Executes a given command in the system's runtime environment.
     *
     * @param command The command to be executed.
     * @return The process exit code, or -1 if execution failed.
     */
    private int executeCommand(String command) {
        try {
            Process process = Runtime.getRuntime().exec(command);

            // Consume stdout
            new Thread(() -> {
                try (InputStream is = process.getInputStream()) {
                    while (is.read() != -1) {
                    }
                } catch (IOException ignored) {
                }
            }).start();

            // Consume stderr
            new Thread(() -> {
                try (InputStream es = process.getErrorStream()) {
                    while (es.read() != -1) {
                    }
                } catch (IOException ignored) {
                }
            }).start();

            return process.waitFor();
        } catch (Exception e) {
            Writer.v().perror(String.format("Problem with the execution of the command: %s", command));
            return -1;
        }
    }

    /**
     * Extracts the keystore file from the application's resources and saves it in a temporary directory.
     * This method retrieves the keystore file using a class loader, and then copies it to a specified temporary folder.
     * If the extraction process encounters any issues, an error is logged.
     *
     * @return The absolute path of the extracted keystore file.
     */

    private String extractKeystore() {
        File keystore = null;
        try {
            String username = System.getProperty("user.name");
            File targetDir = new File(String.format("/tmp/%s", username));
            if (!targetDir.exists()) {
                targetDir.mkdirs();
            }
            InputStream isKeystore = getClass().getClassLoader().getResourceAsStream("keystore.keystore");
            keystore = new File(targetDir, "keystore.keystore");
            FileUtils.copyInputStreamToFile(isKeystore, keystore);
        } catch (Exception e) {
            Writer.v().perror("Problem with the keystore");
        }
        return keystore != null ? keystore.getAbsolutePath() : null;
    }


    /**
     * Replaces the original APK with a new one at a specified path.
     * If the replacement is unsuccessful, an error is logged.
     *
     * @param newPath The file path of the new APK to replace the original.
     */
    private void replaceOriginalApk(String newPath) {
        try {
            Files.move(Paths.get(newPath), Paths.get(apkPath), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            System.out.println(e.getMessage());
            Writer.v().perror("Problem with the replacement of the new APK");
        }
    }
}
