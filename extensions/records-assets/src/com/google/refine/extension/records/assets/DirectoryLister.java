/*
 * Directory Lister
 */

package com.google.refine.extension.records.assets;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.refine.util.JSONUtilities;
import com.google.refine.util.ParsingUtilities;

/**
 * Lists files and directories with lazy loading support
 */
public class DirectoryLister {

    private static final Logger logger = LoggerFactory.getLogger("DirectoryLister");
    private static final int MAX_ITEMS_PER_PAGE = 100;
    private static final int MAX_DEPTH = 5;

    /**
     * List directory contents
     */
    public static ObjectNode listDirectory(String root, String path, int depth, int offset, int limit) 
            throws Exception {
        
        if (logger.isDebugEnabled()) {
            logger.debug("Listing directory: root={}, path={}, depth={}", root, path, depth);
        }

        ObjectNode result = ParsingUtilities.mapper.createObjectNode();

        // Build expected path for error messages
        String expectedPath = root;
        if (path != null && !path.isEmpty()) {
            expectedPath = root + File.separator + path;
        }

        // Validate path
        String fullPath;
        try {
            fullPath = PathValidator.getCanonicalPath(root, path);
        } catch (NoSuchFileException e) {
            logger.warn("Path does not exist: {}", expectedPath);
            JSONUtilities.safePut(result, "status", "error");
            JSONUtilities.safePut(result, "message", "文件路径不存在: " + expectedPath);
            JSONUtilities.safePut(result, "errorPath", expectedPath);
            return result;
        } catch (IOException e) {
            logger.warn("Invalid path: {} - {}", expectedPath, e.getMessage());
            JSONUtilities.safePut(result, "status", "error");
            JSONUtilities.safePut(result, "message", "文件路径错误: " + expectedPath);
            JSONUtilities.safePut(result, "errorPath", expectedPath);
            return result;
        }

        if (fullPath == null) {
            JSONUtilities.safePut(result, "status", "error");
            JSONUtilities.safePut(result, "message", "无效路径: " + expectedPath);
            JSONUtilities.safePut(result, "errorPath", expectedPath);
            return result;
        }

        File dir = new File(fullPath);
        if (!dir.exists()) {
            JSONUtilities.safePut(result, "status", "error");
            JSONUtilities.safePut(result, "message", "目录不存在: " + fullPath);
            JSONUtilities.safePut(result, "errorPath", fullPath);
            return result;
        }

        if (!dir.isDirectory()) {
            JSONUtilities.safePut(result, "status", "error");
            JSONUtilities.safePut(result, "message", "路径不是目录: " + fullPath);
            JSONUtilities.safePut(result, "errorPath", fullPath);
            return result;
        }

        // List files
        File[] files = dir.listFiles();
        if (files == null) {
            JSONUtilities.safePut(result, "status", "error");
            JSONUtilities.safePut(result, "message", "Cannot read directory");
            return result;
        }

        // Sort files
        Arrays.sort(files, (a, b) -> {
            if (a.isDirectory() != b.isDirectory()) {
                return a.isDirectory() ? -1 : 1;
            }
            return a.getName().compareTo(b.getName());
        });

        // Apply pagination
        int totalCount = files.length;
        int start = Math.min(offset, totalCount);
        int end = Math.min(start + limit, totalCount);
        
        ArrayNode items = ParsingUtilities.mapper.createArrayNode();
        for (int i = start; i < end; i++) {
            File file = files[i];
            ObjectNode item = createFileItem(file, root, path, depth);
            items.add(item);
        }

        JSONUtilities.safePut(result, "status", "ok");
        JSONUtilities.safePut(result, "root", root != null ? root : "");
        JSONUtilities.safePut(result, "path", path != null ? path : "");
        JSONUtilities.safePut(result, "currentPath", path != null ? path : "");
        JSONUtilities.safePut(result, "fullPath", fullPath); // Complete absolute path
        JSONUtilities.safePut(result, "depth", depth);
        result.set("items", items);
        JSONUtilities.safePut(result, "totalCount", totalCount);
        JSONUtilities.safePut(result, "offset", start);
        JSONUtilities.safePut(result, "limit", limit);
        JSONUtilities.safePut(result, "hasMore", end < totalCount);

        return result;
    }

    /**
     * Create file item object
     */
    private static ObjectNode createFileItem(File file, String root, String parentPath, int depth) {
        ObjectNode item = ParsingUtilities.mapper.createObjectNode();

        JSONUtilities.safePut(item, "name", file.getName());
        JSONUtilities.safePut(item, "type", file.isDirectory() ? "directory" : "file");
        JSONUtilities.safePut(item, "isDirectory", file.isDirectory());
        JSONUtilities.safePut(item, "size", file.length());
        JSONUtilities.safePut(item, "modified", file.lastModified());
        
        // Build relative path
        String relativePath = parentPath != null && !parentPath.isEmpty() 
            ? parentPath + "/" + file.getName()
            : file.getName();
        JSONUtilities.safePut(item, "path", relativePath);
        
        // Add MIME type for files
        if (file.isFile()) {
            String mimeType = getMimeType(file.getName());
            JSONUtilities.safePut(item, "mimeType", mimeType);
        }
        
        // Add hasChildren flag for directories
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            JSONUtilities.safePut(item, "hasChildren", children != null && children.length > 0);
        }
        
        return item;
    }

    /**
     * Get MIME type from filename
     */
    private static String getMimeType(String filename) {
        String lower = filename.toLowerCase();
        
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        } else if (lower.endsWith(".png")) {
            return "image/png";
        } else if (lower.endsWith(".gif")) {
            return "image/gif";
        } else if (lower.endsWith(".pdf")) {
            return "application/pdf";
        } else if (lower.endsWith(".doc") || lower.endsWith(".docx")) {
            return "application/msword";
        } else if (lower.endsWith(".txt")) {
            return "text/plain";
        } else if (lower.endsWith(".zip")) {
            return "application/zip";
        } else {
            return "application/octet-stream";
        }
    }
}

