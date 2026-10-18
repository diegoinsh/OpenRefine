package org.openrefine.extensions.files.importer;

import java.util.ArrayList;
import java.util.List;

public class CustomElementType {

    public static final String ACTION_INCLUDE = "include";
    public static final String ACTION_ADJUST = "adjust";

    private String name;
    private String key;
    private String action;
    private String description;

    public CustomElementType() {
    }

    public CustomElementType(String name, String key, String action, String description) {
        this.name = name;
        this.key = key != null ? key : generateKeyFromName(name);
        this.action = action != null ? action : ACTION_INCLUDE;
        this.description = description;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public boolean isInclude() {
        return ACTION_INCLUDE.equals(action);
    }

    public boolean isAdjust() {
        return ACTION_ADJUST.equals(action);
    }

    public static String generateKeyFromName(String name) {
        if (name == null || name.trim().isEmpty()) return "custom_";
        StringBuilder sb = new StringBuilder();
        for (char c : name.toCharArray()) {
            if (Character.isLetterOrDigit(c) && c < 128) {
                sb.append(Character.toLowerCase(c));
            } else if (c == '_' || c == '-') {
                sb.append('_');
            } else {
                sb.append('_');
            }
        }
        String key = sb.toString().replaceAll("_+", "_").replaceAll("^_+|_+$", "");
        if (key.isEmpty()) return "custom_";
        if (Character.isDigit(key.charAt(0))) key = "x" + key;
        return key;
    }

    public static List<String> validate(List<CustomElementType> types, ExtractionTemplate template) {
        List<String> errors = new ArrayList<>();
        if (types == null) return errors;
        java.util.Set<String> seenKeys = new java.util.HashSet<>();
        java.util.Set<String> fixedColumns = new java.util.HashSet<>();
        for (String col : template.getColumns()) {
            fixedColumns.add(col);
        }
        int includeCount = 0;
        for (int i = 0; i < types.size(); i++) {
            CustomElementType t = types.get(i);
            String prefix = "第" + (i + 1) + "项";
            if (!t.isInclude() && !t.isAdjust()) {
                errors.add(prefix + ": 操作必须为 include 或 adjust");
                continue;
            }
            if (t.isAdjust()) {
                if (t.getDescription() == null || t.getDescription().trim().isEmpty()) {
                    errors.add(prefix + ": 微调说明不能为空");
                }
                continue;
            }
            if (t.getName() == null || t.getName().trim().isEmpty()) {
                errors.add(prefix + ": 名称不能为空");
                continue;
            }
            if (t.getKey() == null || t.getKey().trim().isEmpty()) {
                errors.add(prefix + ": key 不能为空");
                continue;
            }
            if (fixedColumns.contains(t.getName().trim())) {
                errors.add(prefix + ": 名称 \"" + t.getName() + "\" 与固定列冲突");
                continue;
            }
            if (!seenKeys.add(t.getKey())) {
                errors.add(prefix + ": key \"" + t.getKey() + "\" 重复");
                continue;
            }
            includeCount++;
            if (includeCount > 10) {
                errors.add(prefix + ": include 类型数量超过上限 10");
            }
        }
        return errors;
    }
}
