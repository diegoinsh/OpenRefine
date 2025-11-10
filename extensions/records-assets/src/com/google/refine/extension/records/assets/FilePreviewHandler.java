/*
 * File Preview Handler
 */

package com.google.refine.extension.records.assets;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.refine.util.JSONUtilities;
import com.google.refine.util.ParsingUtilities;

/**
 * Handles file preview generation
 */
public class FilePreviewHandler {

    private static final Logger logger = LoggerFactory.getLogger("FilePreviewHandler");
    private static final int MAX_TEXT_PREVIEW_SIZE = 100 * 1024; // 100KB
    private static final int MAX_IMAGE_PREVIEW_SIZE = 5 * 1024 * 1024; // 5MB

    /**
     * Generate file preview
     */
    public static ObjectNode generatePreview(String root, String path) throws Exception {
        
        if (logger.isDebugEnabled()) {
            logger.debug("Generating preview for: root={}, path={}", root, path);
        }

        ObjectNode result = ParsingUtilities.mapper.createObjectNode();
        
        // Validate path
        String fullPath = PathValidator.getCanonicalPath(root, path);
        if (fullPath == null) {
            JSONUtilities.safePut(result, "status", "error");
            JSONUtilities.safePut(result, "message", "Invalid path");
            return result;
        }

        File file = new File(fullPath);
        if (!file.exists()) {
            JSONUtilities.safePut(result, "status", "error");
            JSONUtilities.safePut(result, "message", "File does not exist");
            return result;
        }

        if (!file.isFile()) {
            JSONUtilities.safePut(result, "status", "error");
            JSONUtilities.safePut(result, "message", "Path is not a file");
            return result;
        }

        // Get file info
        String mimeType = getMimeType(file.getName());
        long fileSize = file.length();

        JSONUtilities.safePut(result, "status", "ok");
        JSONUtilities.safePut(result, "path", path);
        JSONUtilities.safePut(result, "name", file.getName());
        JSONUtilities.safePut(result, "size", fileSize);
        JSONUtilities.safePut(result, "mimeType", mimeType);
        JSONUtilities.safePut(result, "modified", file.lastModified());

        // Generate preview based on file type
        if (isImageFile(mimeType)) {
            generateImagePreview(result, file, mimeType);
        } else if (isTextFile(mimeType)) {
            generateTextPreview(result, file);
        } else if (isPdfFile(mimeType)) {
            generatePdfPreview(result, file);
        } else {
            JSONUtilities.safePut(result, "preview", "");
            JSONUtilities.safePut(result, "previewType", "unsupported");
        }

        return result;
    }

    /**
     * Generate image preview
     */
    private static void generateImagePreview(ObjectNode result, File file, String mimeType) 
            throws IOException {
        
        if (file.length() > MAX_IMAGE_PREVIEW_SIZE) {
            JSONUtilities.safePut(result, "preview", "");
            JSONUtilities.safePut(result, "previewType", "image-too-large");
            JSONUtilities.safePut(result, "message", "Image file is too large for preview");
            return;
        }

        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] imageData = new byte[(int) file.length()];
            fis.read(imageData);
            
            String base64Image = Base64.getEncoder().encodeToString(imageData);
            JSONUtilities.safePut(result, "preview", "data:" + mimeType + ";base64," + base64Image);
            JSONUtilities.safePut(result, "previewType", "image");
        }
    }

    /**
     * Generate text preview
     */
    private static void generateTextPreview(ObjectNode result, File file) throws IOException {
        
        if (file.length() > MAX_TEXT_PREVIEW_SIZE) {
            // Read first 100KB
            byte[] buffer = new byte[MAX_TEXT_PREVIEW_SIZE];
            try (FileInputStream fis = new FileInputStream(file)) {
                int bytesRead = fis.read(buffer);
                String preview = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);
                JSONUtilities.safePut(result, "preview", preview);
                JSONUtilities.safePut(result, "previewType", "text-truncated");
                JSONUtilities.safePut(result, "message", "Text preview truncated to 100KB");
            }
        } else {
            String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            JSONUtilities.safePut(result, "preview", content);
            JSONUtilities.safePut(result, "previewType", "text");
        }
    }

    /**
     * Generate PDF preview
     */
    private static void generatePdfPreview(ObjectNode result, File file) throws IOException {
        
        // For PDF, we just return metadata, not the full content
        JSONUtilities.safePut(result, "preview", "");
        JSONUtilities.safePut(result, "previewType", "pdf");
        JSONUtilities.safePut(result, "message", "PDF preview requires external viewer");
    }

    /**
     * Check if file is an image
     */
    private static boolean isImageFile(String mimeType) {
        return mimeType != null && mimeType.startsWith("image/");
    }

    /**
     * Check if file is text
     */
    private static boolean isTextFile(String mimeType) {
        if (mimeType == null) return false;
        return mimeType.startsWith("text/") || 
               mimeType.equals("application/json") ||
               mimeType.equals("application/xml");
    }

    /**
     * Check if file is PDF
     */
    private static boolean isPdfFile(String mimeType) {
        return "application/pdf".equals(mimeType);
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
        } else if (lower.endsWith(".txt")) {
            return "text/plain";
        } else if (lower.endsWith(".json")) {
            return "application/json";
        } else if (lower.endsWith(".xml")) {
            return "application/xml";
        } else if (lower.endsWith(".csv")) {
            return "text/csv";
        } else if (lower.endsWith(".html") || lower.endsWith(".htm")) {
            return "text/html";
        } else {
            return "application/octet-stream";
        }
    }
}

