package com.jordansamhi.androlog;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class MappingResolver {
    private final Map<String, String> obfuscatedToOriginalClass = new HashMap<>();
    private final Map<String, Map<String, String>> obfuscatedMethodToOriginalByClass = new HashMap<>();

    public static MappingResolver fromFile(String mappingPath) {
        if (mappingPath == null || mappingPath.trim().isEmpty()) {
            return null;
        }

        MappingResolver resolver = new MappingResolver();
        resolver.load(mappingPath);
        return resolver;
    }

    private void load(String mappingPath) {
        try (BufferedReader reader = new BufferedReader(new FileReader(mappingPath))) {
            String line;
            String currentObfuscatedClass = null;

            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }

                if (!line.startsWith(" ") && line.contains(" -> ") && line.endsWith(":")) {
                    String[] parts = line.substring(0, line.length() - 1).split(" -> ");
                    if (parts.length == 2) {
                        String originalClass = parts[0].trim();
                        String obfuscatedClass = parts[1].trim();
                        obfuscatedToOriginalClass.put(obfuscatedClass, originalClass);
                        obfuscatedMethodToOriginalByClass.putIfAbsent(obfuscatedClass, new HashMap<>());
                        currentObfuscatedClass = obfuscatedClass;
                    }
                    continue;
                }

                if (currentObfuscatedClass == null || !line.startsWith("    ") || !line.contains(" -> ")) {
                    continue;
                }

                String trimmed = line.trim();
                String[] methodParts = trimmed.split(" -> ");
                if (methodParts.length != 2) {
                    continue;
                }

                String left = methodParts[0].trim();
                String obfuscatedMethod = methodParts[1].trim();

                int methodStart = left.indexOf('(');
                if (methodStart <= 0) {
                    continue;
                }

                int nameStart = left.lastIndexOf(' ', methodStart);
                if (nameStart <= 0) {
                    continue;
                }

                String originalMethod = left.substring(nameStart + 1, methodStart).trim();
                obfuscatedMethodToOriginalByClass
                        .computeIfAbsent(currentObfuscatedClass, k -> new HashMap<>())
                        .put(obfuscatedMethod, originalMethod);
            }
        } catch (IOException ignored) {
        }
    }

    public String mapClass(String className) {
        return obfuscatedToOriginalClass.getOrDefault(className, className);
    }

    public String mapMethod(String className, String methodName) {
        Map<String, String> byClass = obfuscatedMethodToOriginalByClass.get(className);
        if (byClass == null) {
            return methodName;
        }
        return byClass.getOrDefault(methodName, methodName);
    }

    public String mapMethodSignatureLike(String methodSigFromLog) {
        if (methodSigFromLog == null || !methodSigFromLog.contains(".")) {
            return methodSigFromLog;
        }

        int idx = methodSigFromLog.lastIndexOf('.');
        String cls = methodSigFromLog.substring(0, idx);
        String methodWithSuffix = methodSigFromLog.substring(idx + 1);

        String methodName = methodWithSuffix;
        String suffix = "";
        int parenIdx = methodWithSuffix.indexOf('(');
        if (parenIdx >= 0) {
            methodName = methodWithSuffix.substring(0, parenIdx);
            suffix = methodWithSuffix.substring(parenIdx);
        }

        String mappedClass = mapClass(cls);
        String mappedMethod = mapMethod(cls, methodName);
        return mappedClass + "." + mappedMethod + suffix;
    }
}
