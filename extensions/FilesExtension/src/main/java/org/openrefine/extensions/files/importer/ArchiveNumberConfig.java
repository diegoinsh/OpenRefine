package org.openrefine.extensions.files.importer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 档号拼接规则配置。
 * 根据文件夹/PDF路径按用户配置的规则自动生成档号。
 * 支持两种拼接模式：
 * - separator（分隔符模式）：用指定分隔符连接选定的路径层级
 * - template（模板模式）：用模板表达式替换占位符
 *
 * 参考质量规则"文件资源关联检查"中 ResourceCheckConfig 的路径构建逻辑。
 */
public class ArchiveNumberConfig {

    private static final Logger logger = LoggerFactory.getLogger(ArchiveNumberConfig.class);

    public static final String MODE_SEPARATOR = "separator";
    public static final String MODE_TEMPLATE = "template";

    private List<Integer> pathLevels;   // 参与档号生成的路径层级索引（从根目录起计）
    private String mode;                // "separator" 或 "template"
    private String separator;           // 分隔符，如 "-"
    private String template;            // 模板，如 "{0}-{1}-{2}"
    private String rootPath;            // 根路径（用于计算相对层级）

    public ArchiveNumberConfig() {
        this.pathLevels = new ArrayList<>();
        this.mode = MODE_SEPARATOR;
        this.separator = "-";
        this.template = "";
        this.rootPath = "";
    }

    /**
     * 根据文件夹/PDF路径生成档号。
     *
     * @param unitPath 文件夹或PDF文件的绝对路径
     * @return 生成的档号字符串
     */
    public String generateArchiveNumber(String unitPath) {
        try {
            // 统一路径分隔符并解析
            String normalizedPath = unitPath.replace("\\", "/");
            String normalizedRoot = rootPath != null ? rootPath.replace("\\", "/") : "";

            // 计算相对路径
            String relativePath = normalizedPath;
            if (!normalizedRoot.isEmpty() && normalizedPath.startsWith(normalizedRoot)) {
                relativePath = normalizedPath.substring(normalizedRoot.length());
                if (relativePath.startsWith("/")) {
                    relativePath = relativePath.substring(1);
                }
            }

            // 如果是PDF文件，去掉.pdf后缀
            if (relativePath.toLowerCase().endsWith(".pdf")) {
                relativePath = relativePath.substring(0, relativePath.length() - 4);
            }

            String[] pathParts = relativePath.split("/");

            // 如果未配置层级选择，使用所有层级
            List<String> selectedParts = new ArrayList<>();
            if (pathLevels == null || pathLevels.isEmpty()) {
                for (String part : pathParts) {
                    if (!part.isEmpty()) {
                        selectedParts.add(part);
                    }
                }
            } else {
                for (int level : pathLevels) {
                    if (level >= 0 && level < pathParts.length) {
                        selectedParts.add(pathParts[level]);
                    }
                }
            }

            if (selectedParts.isEmpty()) {
                return "";
            }

            // 按模式拼接
            if (MODE_TEMPLATE.equals(mode) && template != null && !template.isEmpty()) {
                String result = template;
                for (int i = 0; i < selectedParts.size(); i++) {
                    result = result.replace("{" + i + "}", selectedParts.get(i));
                }
                return result;
            } else {
                String sep = (separator != null && !separator.isEmpty()) ? separator : "-";
                return String.join(sep, selectedParts);
            }
        } catch (Exception e) {
            logger.error("Error generating archive number from path: " + unitPath, e);
            return "";
        }
    }

    /**
     * 从JSON配置对象解析 ArchiveNumberConfig。
     */
    public static ArchiveNumberConfig fromJSON(JsonNode configNode) {
        ArchiveNumberConfig config = new ArchiveNumberConfig();
        if (configNode == null || configNode.isNull()) {
            return config;
        }
        try {
            if (configNode.has("mode")) {
                config.mode = configNode.get("mode").asText(MODE_SEPARATOR);
            }
            if (configNode.has("separator")) {
                config.separator = configNode.get("separator").asText("-");
            }
            if (configNode.has("template")) {
                config.template = configNode.get("template").asText("");
            }
            if (configNode.has("rootPath")) {
                config.rootPath = configNode.get("rootPath").asText("");
            }
            if (configNode.has("pathLevels") && configNode.get("pathLevels").isArray()) {
                ArrayNode levels = (ArrayNode) configNode.get("pathLevels");
                for (JsonNode level : levels) {
                    config.pathLevels.add(level.asInt());
                }
            }
        } catch (Exception e) {
            logger.error("Error parsing ArchiveNumberConfig from JSON", e);
        }
        return config;
    }

    // --- Getters and Setters ---

    public List<Integer> getPathLevels() { return pathLevels; }
    public void setPathLevels(List<Integer> pathLevels) { this.pathLevels = pathLevels; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getSeparator() { return separator; }
    public void setSeparator(String separator) { this.separator = separator; }
    public String getTemplate() { return template; }
    public void setTemplate(String template) { this.template = template; }
    public String getRootPath() { return rootPath; }
    public void setRootPath(String rootPath) { this.rootPath = rootPath; }
}

