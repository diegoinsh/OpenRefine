/*
 * JinRefine 容器改造 · module.properties 读取器
 *
 * 自己按行解析而不是 java.util.Properties：既有描述符允许同名键重复
 * （如 butterfly.modules.path），且需要保留逗号分隔的多值语义。
 */

package com.jiongche.refine.kernel;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ModuleDescriptorReader {

    private ModuleDescriptorReader() {
    }

    public static ModuleDescriptor read(File modulePath) {
        Map<String, List<String>> props = readProperties(new File(modulePath, "MOD-INF/module.properties"));
        String name = first(props, "name");
        if (name == null || name.isBlank()) {
            name = modulePath.getName();
        }
        return new ModuleDescriptor(
                name.trim(),
                modulePath,
                first(props, "extends"),
                values(props, "implements"),
                values(props, "requires"),
                values(props, "before"),
                values(props, "after"),
                first(props, "module-impl"),
                first(props, "refine.spi"),
                first(props, "templating.macros"));
    }

    public static Map<String, List<String>> readProperties(File file) {
        if (file == null || !file.isFile()) {
            return Collections.emptyMap();
        }
        Map<String, List<String>> props = new LinkedHashMap<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
                    continue;
                }
                int equal = trimmed.indexOf('=');
                if (equal < 0) {
                    continue;
                }
                String key = trimmed.substring(0, equal).trim();
                String value = trimmed.substring(equal + 1).trim();
                props.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read module descriptor " + file, e);
        }
        return props;
    }

    private static String first(Map<String, List<String>> props, String key) {
        List<String> values = props.get(key);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private static List<String> values(Map<String, List<String>> props, String key) {
        List<String> values = props.get(key);
        if (values == null) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>();
        for (String value : values) {
            for (String part : value.split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    result.add(trimmed);
                }
            }
        }
        return result;
    }
}
