/*
 * File Preview Handler
 */

package com.google.refine.extension.records.assets;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.Iterator;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

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
    /** 缩略图长边像素；面板内实际显示 60x45，留出高清屏余量 */
    private static final int THUMBNAIL_MAX_SIZE = 160;

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
     * Generate thumbnail for image files
     */
    public static ObjectNode generateThumbnail(String root, String path) throws Exception {

        if (logger.isDebugEnabled()) {
            logger.debug("Generating thumbnail for: root={}, path={}", root, path);
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
        if (!file.exists() || !file.isFile()) {
            JSONUtilities.safePut(result, "status", "error");
            JSONUtilities.safePut(result, "message", "File does not exist");
            return result;
        }

        String mimeType = getMimeType(file.getName());

        if (!isImageFile(mimeType)) {
            JSONUtilities.safePut(result, "status", "ok");
            JSONUtilities.safePut(result, "preview", "");
            JSONUtilities.safePut(result, "previewType", "not-image");
            return result;
        }

        // For simplicity, just return full image (frontend can resize)
        // In production, could use ImageIO to create actual thumbnails
        JSONUtilities.safePut(result, "status", "ok");
        generateThumbnailImage(result, file, mimeType);

        return result;
    }

    /**
     * 生成真正的小尺寸缩略图。目录内页数多时，若直接回传整张原图（base64 后体积再膨胀约 1/3），
     * 一次要传上百张原图，缩略图条出图极慢，也会占满浏览器并发连接把主图请求挤到队尾。
     */
    private static void generateThumbnailImage(ObjectNode result, File file, String mimeType) throws IOException {
        BufferedImage source = readDownsampled(file);
        if (source == null) {
            // 无法解码的格式退回原图，保持与改造前一致的行为
            generateImagePreview(result, file, mimeType);
            return;
        }

        BufferedImage scaled = scaleDown(source, THUMBNAIL_MAX_SIZE);
        boolean hasAlpha = scaled.getColorModel().hasAlpha();
        String format = hasAlpha ? "png" : "jpg";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(scaled, format, out)) {
            generateImagePreview(result, file, mimeType);
            return;
        }
        String outMimeType = hasAlpha ? "image/png" : "image/jpeg";
        JSONUtilities.safePut(result, "preview",
                "data:" + outMimeType + ";base64," + Base64.getEncoder().encodeToString(out.toByteArray()));
        JSONUtilities.safePut(result, "previewType", "image");
    }

    /**
     * 解码原图。大图按 1/2、1/4 … 降采样解码，避免先解出整张位图再缩放带来的内存与耗时开销
     * （JPEG 等格式支持按比例解码）。
     */
    private static BufferedImage readDownsampled(File file) {
        ImageReader reader = null;
        try (ImageInputStream input = ImageIO.createImageInputStream(file)) {
            if (input == null) {
                return null;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                return null;
            }
            reader = readers.next();
            reader.setInput(input, false, true);

            int width = reader.getWidth(0);
            int height = reader.getHeight(0);
            int subsample = 1;
            while (width / (subsample * 2) >= THUMBNAIL_MAX_SIZE && height / (subsample * 2) >= THUMBNAIL_MAX_SIZE) {
                subsample *= 2;
            }

            ImageReadParam param = reader.getDefaultReadParam();
            if (subsample > 1) {
                param.setSourceSubsampling(subsample, subsample, 0, 0);
            }
            return reader.read(0, param);
        } catch (Exception e) {
            logger.debug("Thumbnail decode failed for {}: {}", file.getName(), e.getMessage());
            return null;
        } finally {
            if (reader != null) {
                reader.dispose();
            }
        }
    }

    /** 等比缩放到长边不超过 maxSize */
    private static BufferedImage scaleDown(BufferedImage source, int maxSize) {
        int width = source.getWidth();
        int height = source.getHeight();
        double ratio = Math.min(1.0, (double) maxSize / Math.max(width, height));
        int targetWidth = Math.max(1, (int) Math.round(width * ratio));
        int targetHeight = Math.max(1, (int) Math.round(height * ratio));

        int type = source.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage target = new BufferedImage(targetWidth, targetHeight, type);
        Graphics2D g = target.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        } finally {
            g.dispose();
        }
        return target;
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
        } else if (lower.endsWith(".bmp")) {
            return "image/bmp";
        } else if (lower.endsWith(".webp")) {
            return "image/webp";
        } else if (lower.endsWith(".svg")) {
            return "image/svg+xml";
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
        } else if (lower.endsWith(".md")) {
            return "text/markdown";
        } else {
            return "application/octet-stream";
        }
    }
}

