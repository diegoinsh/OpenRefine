/*
 * Security Validator
 */

package com.google.refine.extension.records.assets;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Validates file access security
 */
public class SecurityValidator {

    private static final Logger logger = LoggerFactory.getLogger("SecurityValidator");
    private static final Set<String> BLOCKED_PATTERNS = new HashSet<>();
    
    static {
        // Block common dangerous patterns
        BLOCKED_PATTERNS.add("..");
        BLOCKED_PATTERNS.add("~");
        BLOCKED_PATTERNS.add("$");
        BLOCKED_PATTERNS.add("`");
        BLOCKED_PATTERNS.add("|");
        BLOCKED_PATTERNS.add("&");
        BLOCKED_PATTERNS.add(";");
    }

    /**
     * Validate if path is safe to access
     * Handles cases where root is empty and path is an absolute path
     */
    public static boolean isPathSafe(String root, String path) {
        try {
            // Validate path
            if (path == null) {
                path = "";
            }

            // Check for blocked patterns
            if (containsBlockedPatterns(path)) {
                logger.warn("Path contains blocked patterns: {}", path);
                return false;
            }

            // If root is empty, treat path as absolute path
            if (root == null || root.isEmpty()) {
                if (path.isEmpty()) {
                    logger.warn("Both root and path are empty");
                    return false;
                }
                // For absolute paths without root, just check file exists
                File file = new File(path);
                return file.exists() && file.getCanonicalPath().equals(file.getAbsoluteFile().getCanonicalPath());
            }

            // Get canonical paths
            File rootFile = new File(root);
            String rootCanonical = rootFile.getCanonicalPath();

            String fullPath = path.isEmpty() ? rootCanonical :
                            new File(rootFile, path).getCanonicalPath();

            // Verify that fullPath is under rootCanonical
            if (!fullPath.startsWith(rootCanonical)) {
                logger.warn("Path traversal attempt detected: root={}, path={}, fullPath={}",
                           root, path, fullPath);
                return false;
            }

            // Additional check: ensure the path separator is correct
            if (!fullPath.equals(rootCanonical) &&
                !fullPath.startsWith(rootCanonical + File.separator)) {
                logger.warn("Path is outside root directory: root={}, fullPath={}",
                           rootCanonical, fullPath);
                return false;
            }

            return true;
        } catch (IOException e) {
            logger.error("Error validating path security", e);
            return false;
        }
    }

    /**
     * Validate if path exists and is accessible
     */
    public static boolean isPathAccessible(String root, String path) {
        try {
            if (!isPathSafe(root, path)) {
                return false;
            }

            File rootFile = new File(root);
            File targetFile = path.isEmpty() ? rootFile : new File(rootFile, path);

            return targetFile.exists() && targetFile.canRead();
        } catch (Exception e) {
            logger.error("Error checking path accessibility", e);
            return false;
        }
    }

    /**
     * Validate if path is a directory
     */
    public static boolean isDirectorySafe(String root, String path) {
        try {
            if (!isPathSafe(root, path)) {
                return false;
            }

            File rootFile = new File(root);
            File targetFile = path.isEmpty() ? rootFile : new File(rootFile, path);

            return targetFile.isDirectory();
        } catch (Exception e) {
            logger.error("Error checking if path is directory", e);
            return false;
        }
    }

    /**
     * Validate if path is a file
     */
    public static boolean isFileSafe(String root, String path) {
        try {
            if (!isPathSafe(root, path)) {
                return false;
            }

            File rootFile = new File(root);
            File targetFile = path.isEmpty() ? rootFile : new File(rootFile, path);

            return targetFile.isFile();
        } catch (Exception e) {
            logger.error("Error checking if path is file", e);
            return false;
        }
    }

    /**
     * Check if path contains blocked patterns
     */
    private static boolean containsBlockedPatterns(String path) {
        if (path == null) {
            return false;
        }

        for (String pattern : BLOCKED_PATTERNS) {
            if (path.contains(pattern)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Validate allowed roots configuration
     */
    public static boolean isRootAllowed(String root, List<String> allowedRoots) {
        if (allowedRoots == null || allowedRoots.isEmpty()) {
            // If no allowed roots specified, allow any root
            return true;
        }

        try {
            File rootFile = new File(root);
            String rootCanonical = rootFile.getCanonicalPath();

            for (String allowedRoot : allowedRoots) {
                File allowedFile = new File(allowedRoot);
                String allowedCanonical = allowedFile.getCanonicalPath();

                if (rootCanonical.equals(allowedCanonical) || 
                    rootCanonical.startsWith(allowedCanonical + File.separator)) {
                    return true;
                }
            }

            logger.warn("Root is not in allowed roots: root={}, allowedRoots={}", 
                       root, allowedRoots);
            return false;
        } catch (IOException e) {
            logger.error("Error validating allowed roots", e);
            return false;
        }
    }

    /**
     * Sanitize path for display
     */
    public static String sanitizePath(String path) {
        if (path == null) {
            return "";
        }

        // Remove any null bytes
        path = path.replace("\0", "");

        // Normalize path separators
        path = path.replace("\\", "/");

        return path;
    }
}

