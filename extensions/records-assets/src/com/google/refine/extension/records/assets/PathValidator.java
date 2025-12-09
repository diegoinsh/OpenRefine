/*
 * Path Validator for Security
 */

package com.google.refine.extension.records.assets;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Validates file paths for security
 * Prevents directory traversal attacks
 */
public class PathValidator {

    private static final Logger logger = LoggerFactory.getLogger("PathValidator");

    /**
     * Validate that a path is within allowed roots
     * 
     * @param root The root directory
     * @param path The relative path
     * @param allowedRoots List of allowed root directories (null or empty = no restriction)
     * @return true if path is valid and safe
     */
    public static boolean isValidPath(String root, String path, List<String> allowedRoots) {
        if (root == null || root.isEmpty()) {
            logger.warn("Root path is null or empty");
            return false;
        }

        // If allowedRoots is specified, check if root is in the list
        if (allowedRoots != null && !allowedRoots.isEmpty()) {
            boolean rootAllowed = false;
            for (String allowedRoot : allowedRoots) {
                if (root.equals(allowedRoot) || root.startsWith(allowedRoot + File.separator)) {
                    rootAllowed = true;
                    break;
                }
            }
            if (!rootAllowed) {
                logger.warn("Root path not in allowed roots: {}", root);
                return false;
            }
        }

        try {
            // Get canonical paths to prevent directory traversal
            Path rootPath = Paths.get(root).toRealPath();
            Path fullPath;

            if (path == null || path.isEmpty()) {
                fullPath = rootPath;
            } else {
                fullPath = rootPath.resolve(path).toRealPath();
            }

            // Check if the resolved path is still within the root
            if (!fullPath.startsWith(rootPath)) {
                logger.warn("Path traversal detected: {} -> {}", root, path);
                return false;
            }

            return true;
        } catch (IOException e) {
            logger.warn("Error validating path: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Get the canonical path
     * Handles cases where root is empty and path is an absolute path
     */
    public static String getCanonicalPath(String root, String path) throws IOException {
        // If root is empty or null, treat path as absolute path
        if (root == null || root.isEmpty()) {
            if (path == null || path.isEmpty()) {
                throw new IOException("Both root and path are empty");
            }
            return Paths.get(path).toRealPath().toString();
        }

        Path rootPath = Paths.get(root).toRealPath();
        Path fullPath;

        if (path == null || path.isEmpty()) {
            fullPath = rootPath;
        } else {
            fullPath = rootPath.resolve(path).toRealPath();
        }

        return fullPath.toString();
    }

    /**
     * Check if a path exists
     */
    public static boolean pathExists(String root, String path) {
        try {
            String canonicalPath = getCanonicalPath(root, path);
            return Files.exists(Paths.get(canonicalPath));
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Check if a path is a directory
     */
    public static boolean isDirectory(String root, String path) {
        try {
            String canonicalPath = getCanonicalPath(root, path);
            return Files.isDirectory(Paths.get(canonicalPath));
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Check if a path is a file
     */
    public static boolean isFile(String root, String path) {
        try {
            String canonicalPath = getCanonicalPath(root, path);
            return Files.isRegularFile(Paths.get(canonicalPath));
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Get file size
     */
    public static long getFileSize(String root, String path) throws IOException {
        String canonicalPath = getCanonicalPath(root, path);
        return Files.size(Paths.get(canonicalPath));
    }

    /**
     * Get file MIME type
     */
    public static String getMimeType(String root, String path) throws IOException {
        String canonicalPath = getCanonicalPath(root, path);
        String mimeType = Files.probeContentType(Paths.get(canonicalPath));
        return mimeType != null ? mimeType : "application/octet-stream";
    }

    /**
     * List directory contents
     */
    public static File[] listDirectory(String root, String path) throws IOException {
        String canonicalPath = getCanonicalPath(root, path);
        File dir = new File(canonicalPath);
        
        if (!dir.isDirectory()) {
            return new File[0];
        }

        File[] files = dir.listFiles();
        return files != null ? files : new File[0];
    }
}

