package org.openrefine.extensions.files.importer;

import java.util.ArrayList;
import java.util.List;

public class CustomElementType {

    public static final String ACTION_INCLUDE = "include";
    public static final String ACTION_EXCLUDE = "exclude";

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

    public boolean isExclude() {
        return ACTION_EXCLUDE.equals(action);
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
        if (sb.length() == 0) return "custom_";
        if (Character.isDigit(sb.charAt(0))) sb.insert(0, 'x');
        return sb.toString();
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
            if (t.getName() == null || t.getName().trim().isEmpty()) {
                errors.add(prefix + ": 名称不能为空");
                continue;
            }
            if (t.getKey() == null || t.getKey().trim().isEmpty()) {
                errors.add(prefix + ": key 不能为空");
                continue;
            }
            if (fixedColumns.contains(t.getName())) {
                errors.add(prefix + ": 名称 \"" + t.getName() + "\" 与固定列冲突");
                continue;
            }
            if (!t.isInclude() && !t.isExclude()) {
                errors.add(prefix + ": 操作必须为 include 或 exclude");
                continue;
            }
            if (!seenKeys.add(t.getKey())) {
                errors.add(prefix + ": key \"" + t.getKey() + "\" 重复");
                continue;
            }
            if (t.isInclude()) {
                includeCount++;
                if (includeCount > 10) {
                    errors.add(prefix + ": include 类型数量超过上限 10");
                }
            }
        }
        return errors;
    }
}
