package com.google.refine.extension.quality.checker;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.refine.extension.quality.model.CheckResult;
import com.google.refine.extension.quality.model.CheckResult.CheckError;
import com.google.refine.extension.quality.model.QualityRulesConfig;
import com.google.refine.extension.quality.model.ResourceCheckConfig;
import com.google.refine.model.Cell;
import com.google.refine.model.Project;
import com.google.refine.model.Row;

/**
 * Checker for data existence validation.
 * Ensures that each folder at the same level as image folders has a corresponding data entry.
 */
public class DataExistenceChecker {

    private static final Logger logger = LoggerFactory.getLogger(DataExistenceChecker.class);

    private final Project project;
    private final QualityRulesConfig rules;
    private final String basePath;
    private final Set<String> dataPaths;
    private final Map<String, Integer> columnIndexMap;

    public DataExistenceChecker(Project project, QualityRulesConfig rules, Set<String> dataPaths, Map<String, Integer> columnIndexMap) {
        this.project = project;
        this.rules = rules;
        this.dataPaths = dataPaths;
        this.columnIndexMap = columnIndexMap;
        ResourceCheckConfig config = rules != null ? rules.getResourceConfig() : null;
        this.basePath = (config != null && config.getBasePath() != null) ? config.getBasePath() : "";
    }

    public CheckResult runCheck() {
        CheckResult result = new CheckResult("resource");

        if (basePath == null || basePath.isEmpty()) {
            logger.warn("[DataExistenceChecker] Base path is empty, skipping check");
            result.complete();
            return result;
        }

        File baseDir = new File(basePath);
        if (!baseDir.exists() || !baseDir.isDirectory()) {
            logger.warn("[DataExistenceChecker] Base path does not exist or is not a directory: {}", basePath);
            result.complete();
            return result;
        }

        logger.info("[DataExistenceChecker] Starting data existence check at: {}", basePath);

        // Extract the parent directory from data paths to find the scan directory
        // Data paths are like: D:\...\0016-WS·2016-Y\0016-WS·2016-Y-0019\
        // We need to scan from D:\...\0016-WS·2016-Y
        File scanDir = baseDir;
        if (!dataPaths.isEmpty()) {
            String firstPath = dataPaths.iterator().next();
            File firstFile = new File(firstPath);
            scanDir = firstFile.getParentFile();
            logger.info("[DataExistenceChecker] Using parent directory as scan target: {}", scanDir.getAbsolutePath());
        }

        // Collect folders from the scan directory (at the level of item folders)
        Set<String> foldersToCheck = collectFoldersFromScanDir(scanDir);
        logger.info("[DataExistenceChecker] Found {} folders to check", foldersToCheck.size());

        int missingDataCount = 0;
        for (String folderPath : foldersToCheck) {
            // Normalize folder path by removing trailing backslash if present
            String normalizedFolderPath = folderPath.endsWith("\\") ? folderPath.substring(0, folderPath.length() - 1) : folderPath;
            
            // Check if this folder has a corresponding data entry
            boolean hasDataEntry = false;
            for (String dataPath : dataPaths) {
                // Normalize data path by removing trailing backslash if present
                String normalizedDataPath = dataPath.endsWith("\\") ? dataPath.substring(0, dataPath.length() - 1) : dataPath;
                if (normalizedFolderPath.equals(normalizedDataPath)) {
                    hasDataEntry = true;
                    break;
                }
            }
            
            if (!hasDataEntry) {
                int rowIndex = findRowIndexForPath(folderPath);
                CheckError error = new CheckError(
                    rowIndex,
                    "resource_path",
                    folderPath,
                    "data_existence",
                    "data-quality-extension/error-msg-data-existence"
                );
                error.setCategory("resource");
                result.addError(error);
                missingDataCount++;
            }
        }

        logger.info("[DataExistenceChecker] Check complete. Found {} folders without data entries", missingDataCount);
        result.complete();
        return result;
    }

    private Set<String> collectFoldersToCheck(File baseDir) {
        Set<String> folders = new HashSet<>();
        collectFoldersRecursive(baseDir, baseDir, folders);
        return folders;
    }

    private Set<String> collectFoldersFromScanDir(File scanDir) {
        Set<String> folders = new HashSet<>();
        if (scanDir == null || !scanDir.exists() || !scanDir.isDirectory()) {
            return folders;
        }
        
        File[] files = scanDir.listFiles();
        if (files == null) {
            return folders;
        }
        
        for (File file : files) {
            if (file.isDirectory()) {
                String folderName = file.getName();
                if (isLastLevelFolder(folderName)) {
                    folders.add(file.getAbsolutePath());
                }
            }
        }
        
        return folders;
    }

    private void collectFoldersRecursive(File currentDir, File baseDir, Set<String> folders) {
        if (!currentDir.exists() || !currentDir.isDirectory()) {
            return;
        }
        
        File[] files = currentDir.listFiles();
        
        if (files == null) {
            return;
        }

        for (File file : files) {
            if (file.isDirectory()) {
                String absolutePath = file.getAbsolutePath();
                String folderName = file.getName();
                
                int currentDepth = getFolderDepth(absolutePath, baseDir.getAbsolutePath());
                int expectedDepth = getExpectedFolderDepth();

                if (currentDepth == expectedDepth && isLastLevelFolder(folderName)) {
                    folders.add(absolutePath);
                }

                collectFoldersRecursive(file, baseDir, folders);
            }
        }
    }

    private int getFolderDepth(String folderPath, String basePath) {
        // Calculate the folder depth relative to base path
        if (basePath == null || basePath.isEmpty()) {
            return 0;
        }

        String normalizedBasePath = new File(basePath).getAbsolutePath();
        String normalizedFolderPath = new File(folderPath).getAbsolutePath();

        if (!normalizedFolderPath.startsWith(normalizedBasePath)) {
            return 0;
        }

        String relativePath = normalizedFolderPath.substring(normalizedBasePath.length());
        // Count the number of path separators to get depth
        int depth = 0;
        for (char c : relativePath.toCharArray()) {
            if (c == '\\' || c == '/') {
                depth++;
            }
        }
        return depth;
    }

    private int getExpectedFolderDepth() {
        ResourceCheckConfig config = rules != null ? rules.getResourceConfig() : null;
        if (config == null || config.getPathFields() == null) {
            logger.warn("[DataExistenceChecker] ResourceCheckConfig or pathFields is null");
            return 0;
        }
        // pathFields includes basePath, so we subtract 1 to get the actual folder depth
        int depth = config.getPathFields().size() - 1;
        logger.info("[DataExistenceChecker] Expected folder depth (pathFields={}, excluding basePath={}): {}", 
            config.getPathFields().size(), depth);
        return depth > 0 ? depth : 0;
    }

    private boolean isLastLevelFolder(String folderName) {
        // Check if folder name ends with a numeric suffix (item number)
        // Pattern: something like "0016-WS·2016-Y-0019" where "0019" is the item number
        // The item number is typically the last numeric part after a hyphen or other separator
        
        // Check if folder name contains a numeric suffix
        String[] parts = folderName.split("[-·_]");
        if (parts.length == 0) {
            return false;
        }

        // Get the last part
        String lastPart = parts[parts.length - 1];
        
        // Check if the last part is numeric (item number)
        try {
            Integer.parseInt(lastPart);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private boolean isImageFolderLevel(File folder, File baseDir) {
        // Check if this folder is at the same level as image folders
        // We consider a folder to be at image folder level if it's a subfolder of the base path
        String absolutePath = folder.getAbsolutePath();
        String basePathStr = baseDir.getAbsolutePath();

        if (absolutePath.equals(basePathStr)) {
            return false; // Skip the base directory itself
        }

        // Check if this folder is a subdirectory of the base path
        if (absolutePath.startsWith(basePathStr)) {
            return true;
        }

        return false;
    }

    private int findRowIndexForPath(String folderPath) {
        if (project == null || project.rows == null) {
            return -1;
        }

        ResourceCheckConfig config = rules != null ? rules.getResourceConfig() : null;
        if (config == null || config.getPathFields() == null || config.getPathFields().isEmpty()) {
            return -1;
        }

        // Normalize folder path for comparison
        String normalizedFolderPath = new File(folderPath).getAbsolutePath();

        // Iterate through all rows to find a match
        for (int rowIndex = 0; rowIndex < project.rows.size(); rowIndex++) {
            Row row = project.rows.get(rowIndex);
            String rowPath = buildResourcePathForRow(row);
            if (rowPath != null) {
                String normalizedRowPath = new File(rowPath).getAbsolutePath();
                if (normalizedRowPath.equals(normalizedFolderPath)) {
                    return rowIndex;
                }
            }
        }

        return -1;
    }

    private String buildResourcePathForRow(Row row) {
        ResourceCheckConfig config = rules != null ? rules.getResourceConfig() : null;
        if (config == null || config.getPathFields() == null || config.getPathFields().isEmpty()) {
            return null;
        }

        List<String> pathFields = config.getPathFields();
        List<String> values = new ArrayList<>();
        for (String fieldName : pathFields) {
            Integer idx = columnIndexMap.get(fieldName);
            if (idx == null) continue;
            Cell cell = row.getCell(idx);
            String val = getCellValue(cell);
            if (val != null && !val.isEmpty()) {
                values.add(val);
            }
        }

        if (values.isEmpty()) {
            return null;
        }

        StringBuilder path = new StringBuilder();

        // Add base path
        String basePath = config.getBasePath();
        if (basePath != null && !basePath.isEmpty()) {
            path.append(basePath);
            if (!basePath.endsWith("/") && !basePath.endsWith("\\")) {
                path.append(File.separator);
            }
        }

        // Build path based on mode
        if ("template".equals(config.getPathMode()) && config.getTemplate() != null && !config.getTemplate().isEmpty()) {
            String template = config.getTemplate();
            for (int i = 0; i < values.size(); i++) {
                template = template.replace("{" + i + "}", values.get(i));
            }
            path.append(template);
        } else {
            String sep = config.getSeparator();
            if (sep == null || sep.isEmpty()) {
                sep = File.separator;
            }
            path.append(String.join(sep, values));
        }

        return path.toString();
    }

    private String getCellValue(Cell cell) {
        if (cell == null || cell.value == null) {
            return null;
        }
        return cell.value.toString().trim();
    }
}
