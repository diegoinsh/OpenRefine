/*
 * Data Quality Extension - File Image Renderer
 *
 * Renders image files and PDF pages to raster images, and crops regions
 * (used as the image source for OCR recognition of a user-drawn rectangle).
 */

package com.google.refine.extension.quality.util;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.Base64;

import javax.imageio.ImageIO;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FileImageRenderer {

    private static final Logger logger = LoggerFactory.getLogger(FileImageRenderer.class);

    /** PDF 页渲染 DPI：150 兼顾清晰度与上传体积 */
    public static final int PDF_RENDER_DPI = 150;

    private static final String[] IMAGE_EXTENSIONS = {
            ".jpg", ".jpeg", ".png", ".bmp", ".gif", ".tif", ".tiff"
    };

    public static boolean isImageFile(String fileName) {
        String lower = fileName == null ? "" : fileName.toLowerCase();
        for (String ext : IMAGE_EXTENSIONS) {
            if (lower.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isPdfFile(String fileName) {
        return fileName != null && fileName.toLowerCase().endsWith(".pdf");
    }

    /** 按文件类型渲染为位图：图片直接读取，PDF 渲染指定页（1 起） */
    public static BufferedImage render(File file, int pageNumber) throws IOException {
        if (isPdfFile(file.getName())) {
            return renderPdfPage(file, pageNumber);
        }
        return readImage(file);
    }

    public static BufferedImage readImage(File file) throws IOException {
        BufferedImage image = ImageIO.read(file);
        if (image == null) {
            throw new IOException("无法解析图片文件: " + file.getName());
        }
        return toRgb(image);
    }

    public static BufferedImage renderPdfPage(File file, int pageNumber) throws IOException {
        try (PDDocument document = PDDocument.load(file)) {
            int pageCount = document.getNumberOfPages();
            if (pageCount <= 0) {
                throw new IOException("PDF 没有可用页面");
            }
            int target = Math.max(1, Math.min(pageNumber, pageCount));
            if (target != pageNumber) {
                logger.warn("请求页码 {} 超出范围，渲染第 {} 页", pageNumber, target);
            }
            PDFRenderer renderer = new PDFRenderer(document);
            return toRgb(renderer.renderImageWithDPI(target - 1, PDF_RENDER_DPI, ImageType.RGB));
        }
    }

    public static int getPdfPageCount(File file) throws IOException {
        try (PDDocument document = PDDocument.load(file)) {
            return document.getNumberOfPages();
        }
    }

    /** 按区域裁剪；越界坐标收敛到图像范围，过小区域直接报错 */
    public static BufferedImage crop(BufferedImage source, int x, int y, int width, int height)
            throws IOException {
        int sx = Math.max(0, Math.min(x, source.getWidth() - 1));
        int sy = Math.max(0, Math.min(y, source.getHeight() - 1));
        int sw = Math.min(width, source.getWidth() - sx);
        int sh = Math.min(height, source.getHeight() - sy);
        if (sw < 2 || sh < 2) {
            throw new IOException("裁剪区域过小");
        }
        return toRgb(source.getSubimage(sx, sy, sw, sh));
    }

    public static byte[] toPngBytes(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    public static String toPngDataUrl(BufferedImage image) throws IOException {
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(toPngBytes(image));
    }

    private static BufferedImage toRgb(BufferedImage image) {
        if (image.getType() == BufferedImage.TYPE_INT_RGB) {
            return image;
        }
        BufferedImage rgb = new BufferedImage(image.getWidth(), image.getHeight(),
                BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = rgb.createGraphics();
        graphics.drawImage(image, 0, 0, Color.WHITE, null);
        graphics.dispose();
        return rgb;
    }
}
