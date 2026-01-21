/*
 * Data Quality Extension - Image Check Error
 * Individual error entry for image quality check results
 */
package com.google.refine.extension.quality.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ImageCheckError {

    private static final Logger logger = LoggerFactory.getLogger(ImageCheckError.class);

    @JsonProperty("rowIndex")
    private int rowIndex;

    @JsonProperty("columnName")
    private String columnName;

    @JsonProperty("imagePath")
    private String imagePath;

    @JsonProperty("imageName")
    private String imageName;

    @JsonProperty("hiddenFileName")
    private String hiddenFileName;

    @JsonProperty("errorCode")
    private String errorCode;

    @JsonProperty("errorType")
    private String errorType;

    @JsonProperty("category")
    private String category;

    @JsonProperty("message")
    private String message;

    @JsonProperty("severity")
    private String severity;

    @JsonProperty("details")
    private ImageCheckErrorDetails details;

    @JsonProperty("duplicateImagePaths")
    private java.util.List<String> duplicateImagePaths;

    public ImageCheckError() {
        this.severity = "error";
    }

    public ImageCheckError(int rowIndex, String columnName, String imagePath, String errorType, String message) {
        this();
        this.rowIndex = rowIndex;
        this.columnName = columnName;
        this.imagePath = imagePath;
        this.errorType = errorType;
        this.message = message;
    }

    public int getRowIndex() {
        return rowIndex;
    }

    public void setRowIndex(int rowIndex) {
        this.rowIndex = rowIndex;
    }

    public String getColumnName() {
        return columnName;
    }

    public void setColumnName(String columnName) {
        this.columnName = columnName;
    }

    public String getImagePath() {
        return imagePath;
    }

    public void setImagePath(String imagePath) {
        this.imagePath = imagePath;
    }

    public String getImageName() {
        return imageName;
    }

    public void setImageName(String imageName) {
        this.imageName = imageName;
    }

    public String getHiddenFileName() {
        return hiddenFileName;
    }

    public void setHiddenFileName(String hiddenFileName) {
        this.hiddenFileName = hiddenFileName;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public String getErrorType() {
        return errorType;
    }

    public void setErrorType(String errorType) {
        this.errorType = errorType;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public ImageCheckErrorDetails getDetails() {
        return details;
    }

    public void setDetails(ImageCheckErrorDetails details) {
        this.details = details;
    }

    public java.util.List<String> getDuplicateImagePaths() {
        return duplicateImagePaths;
    }

    public void setDuplicateImagePaths(java.util.List<String> duplicateImagePaths) {
        this.duplicateImagePaths = duplicateImagePaths;
    }

    public static ImageCheckError createFormatError(int rowIndex, String columnName, String imagePath,
            String imageName, String expectedFormat, String actualFormat) {
        ImageCheckError error = new ImageCheckError(rowIndex, columnName, imagePath, "format_error",
                String.format("图像格式错误: 期望 %s, 实际 %s", expectedFormat, actualFormat));
        error.setErrorCode("FORMAT_ERROR");
        error.setImageName(imageName);
        error.setHiddenFileName(imageName);
        error.setCategory(ImageCheckCategoryType.可用性.getCode());
        error.setSeverity("error");
        error.setDetails(new ImageCheckErrorDetails(expectedFormat, actualFormat, null, null));
        return error;
    }

    public static ImageCheckError createIllegalFileError(int rowIndex, String columnName, String imagePath,
            String imageName, String allowedFormats, String actualFormat) {
        ImageCheckError error = new ImageCheckError(rowIndex, columnName, imagePath, "illegal_file",
                String.format("非法归档文件: 允许的格式 %s, 实际 %s", allowedFormats, actualFormat));
        error.setErrorCode("ILLEGAL_FILE_ERROR");
        error.setImageName(imageName);
        error.setHiddenFileName(imageName);
        error.setCategory(ImageCheckCategoryType.安全性.getCode());
        error.setSeverity("error");
        error.setDetails(new ImageCheckErrorDetails(allowedFormats, actualFormat, null, null));
        return error;
    }

    public static ImageCheckError createSecurityError(int rowIndex, String columnName, String imagePath,
            String errorTitle, String errorMessage) {
        ImageCheckError error = new ImageCheckError(rowIndex, columnName, imagePath, "security_check",
                errorMessage);
        error.setErrorCode("SECURITY_ERROR");
        error.setImageName(errorTitle);
        error.setHiddenFileName(errorTitle);
        error.setCategory(ImageCheckCategoryType.安全性.getCode());
        error.setSeverity("error");
        error.setDetails(new ImageCheckErrorDetails(errorTitle, errorMessage, null, null));
        return error;
    }

    public static ImageCheckError createDpiError(int rowIndex, String columnName, String imagePath,
            String imageName, int minDpi, int actualDpi) {
        ImageCheckError error = new ImageCheckError(rowIndex, columnName, imagePath, "dpi_error",
                String.format("DPI值不足: 期望 >= %d, 实际 %d", minDpi, actualDpi));
        error.setErrorCode("DPI_ERROR");
        error.setImageName(imageName);
        error.setHiddenFileName(imageName);
        error.setCategory(ImageCheckCategoryType.可用性.getCode());
        error.setSeverity("error");
        error.setDetails(new ImageCheckErrorDetails(null, null, minDpi, actualDpi));
        return error;
    }

    public static ImageCheckError createSizeError(int rowIndex, String columnName, String imagePath,
            String imageName, long minKb, long maxKb, long actualKb) {
        ImageCheckError error = new ImageCheckError(rowIndex, columnName, imagePath, "size_error",
                String.format("文件大小超出范围: 期望 %d-%d KB, 实际 %d KB", minKb, maxKb, actualKb));
        error.setErrorCode("SIZE_ERROR");
        error.setImageName(imageName);
        error.setHiddenFileName(imageName);
        error.setCategory(ImageCheckCategoryType.可用性.getCode());
        error.setSeverity("error");
        return error;
    }

    public static ImageCheckError createQualityError(int rowIndex, String columnName, String imagePath,
            String imageName, int minRatio, int actualRatio) {
        ImageCheckError error = new ImageCheckError(rowIndex, columnName, imagePath, "quality_error",
                String.format("图像质量不足: 期望压缩比 >= %d%%, 实际 %d%%", minRatio, actualRatio));
        error.setErrorCode("QUALITY_ERROR");
        error.setImageName(imageName);
        error.setHiddenFileName(imageName);
        error.setCategory(ImageCheckCategoryType.可用性.getCode());
        error.setSeverity("error");
        error.setDetails(new ImageCheckErrorDetails(null, null, minRatio, actualRatio));
        return error;
    }

    public static ImageCheckError createBlankPageError(int rowIndex, String columnName, String imagePath,
            String imageName) {
        ImageCheckError error = new ImageCheckError(rowIndex, columnName, imagePath, "blank_page_error",
                "检测到空白页");
        error.setErrorCode("BLANK_PAGE_ERROR");
        error.setImageName(imageName);
        error.setHiddenFileName(imageName);
        error.setCategory(ImageCheckCategoryType.完整性.getCode());
        error.setSeverity("warning");
        return error;
    }

    public static ImageCheckError createStainError(int rowIndex, String columnName, String imagePath,
            String imageName, int stainCount, int threshold, Integer locationX, Integer locationY) {
        ImageCheckError error = new ImageCheckError(rowIndex, columnName, imagePath, "stain_error",
                String.format("检测到污点: %d个 > 阈值 %d", stainCount, threshold));
        error.setErrorCode("STAIN_ERROR");
        error.setImageName(imageName);
        error.setHiddenFileName(imageName);
        logger.info("[ImageCheckError] createStainError - imageName: {}, hiddenFileName: {}, locationX: {}, locationY: {}",
            imageName, error.getHiddenFileName(), locationX, locationY);
        error.setCategory(ImageCheckCategoryType.完整性.getCode());
        error.setSeverity("warning");
        error.setDetails(new ImageCheckErrorDetails(null, null, stainCount, threshold));
        return error;
    }

    public static ImageCheckError createHoleError(int rowIndex, String columnName, String imagePath,
            String imageName, int holeCount, int threshold, Integer locationX, Integer locationY) {
        ImageCheckError error = new ImageCheckError(rowIndex, columnName, imagePath, "hole_error",
                String.format("检测到装订孔: %d个 > 阈值 %d", holeCount, threshold));
        error.setErrorCode("HOLE_ERROR");
        error.setImageName(imageName);
        error.setHiddenFileName(imageName);
        logger.info("[ImageCheckError] createHoleError - imageName: {}, hiddenFileName: {}, locationX: {}, locationY: {}",
            imageName, error.getHiddenFileName(), locationX, locationY);
        error.setCategory(ImageCheckCategoryType.完整性.getCode());
        error.setSeverity("warning");
        error.setDetails(new ImageCheckErrorDetails(null, null, holeCount, threshold));
        return error;
    }

    public static ImageCheckError createSkewError(int rowIndex, String columnName, String imagePath,
            String imageName, float skewAngle, float tolerance) {
        ImageCheckError error = new ImageCheckError(rowIndex, columnName, imagePath, "bias",
                String.format("图像倾斜: %.2f° > 容差 %.2f°", skewAngle, tolerance));
        error.setErrorCode("SKEW_ERROR");
        error.setImageName(imageName);
        error.setHiddenFileName(imageName);
        error.setCategory(ImageCheckCategoryType.完整性.getCode());
        error.setSeverity("warning");
        error.setDetails(new ImageCheckErrorDetails(null, null, skewAngle, tolerance));
        return error;
    }

    public static ImageCheckError createEdgeError(int rowIndex, String columnName, String imagePath,
            String imageName) {
        ImageCheckError error = new ImageCheckError(rowIndex, columnName, imagePath, "edge_error",
                "检测到黑边");
        error.setErrorCode("EDGE_ERROR");
        error.setImageName(imageName);
        error.setHiddenFileName(imageName);
        error.setCategory(ImageCheckCategoryType.完整性.getCode());
        error.setSeverity("warning");
        return error;
    }

    public static ImageCheckError createDamageError(int rowIndex, String columnName, String imagePath,
            String imageName) {
        ImageCheckError error = new ImageCheckError(rowIndex, columnName, imagePath, "damage_error",
                "文件损坏或无法读取");
        error.setErrorCode("DAMAGE_ERROR");
        error.setImageName(imageName);
        error.setHiddenFileName(imageName);
        error.setCategory(ImageCheckCategoryType.可用性.getCode());
        error.setSeverity("error");
        return error;
    }

    public static ImageCheckError createRepeatImageError(int rowIndex, String columnName, String imagePath,
            String imageName, int duplicateCount) {
        ImageCheckError error = new ImageCheckError(rowIndex, columnName, imagePath, "repeatimage_error",
                String.format("发现重复图片，重复 %d 次", duplicateCount));
        error.setErrorCode("REPEAT_IMAGE_ERROR");
        error.setImageName(imageName);
        error.setHiddenFileName(imageName);
        error.setCategory(ImageCheckCategoryType.真实性.getCode());
        error.setSeverity("error");
        error.setDetails(new ImageCheckErrorDetails(null, null, duplicateCount, null));
        return error;
    }

    public static ImageCheckError createRepeatImageError(int rowIndex, String columnName, String imagePath,
            String imageName, int duplicateCount, java.util.List<String> duplicateImagePaths) {
        ImageCheckError error = new ImageCheckError(rowIndex, columnName, imagePath, "repeatimage_error",
                String.format("发现重复图片，重复 %d 次", duplicateCount));
        error.setErrorCode("REPEAT_IMAGE_ERROR");
        error.setImageName(imageName);
        error.setHiddenFileName(imageName);
        error.setCategory(ImageCheckCategoryType.真实性.getCode());
        error.setSeverity("error");
        error.setDetails(new ImageCheckErrorDetails(null, null, duplicateCount, null));
        error.setDuplicateImagePaths(duplicateImagePaths);
        return error;
    }

    public static ImageCheckError createEmptyFolderError(int rowIndex, String columnName, String imagePath) {
        ImageCheckError error = new ImageCheckError(rowIndex, columnName, imagePath, "empty_folder_error",
                "文件夹为空");
        error.setErrorCode("EMPTY_FOLDER_ERROR");
        error.setCategory(ImageCheckCategoryType.完整性.getCode());
        error.setSeverity("warning");
        return error;
    }

    public static ImageCheckError createQuantityError(int rowIndex, String columnName, String imagePath,
            Integer expected, int actual) {
        ImageCheckError error = new ImageCheckError(rowIndex, columnName, imagePath, "quantity_error",
                String.format("文件数量不匹配: 期望 %s, 实际 %d", expected != null ? expected : "不限", actual));
        error.setErrorCode("QUANTITY_ERROR");
        error.setCategory(ImageCheckCategoryType.完整性.getCode());
        error.setSeverity("warning");
        error.setDetails(new ImageCheckErrorDetails(expected, actual, null, null));
        return error;
    }

    public static ImageCheckError createPageSizeError(int rowIndex, String columnName, String imagePath,
            Integer expected, int actual) {
        ImageCheckError error = new ImageCheckError(rowIndex, columnName, imagePath, "page_size_error",
                String.format("篇幅数量不匹配: 期望 %s, 实际 %d", expected != null ? expected : "不限", actual));
        error.setErrorCode("PAGE_SIZE_ERROR");
        error.setCategory(ImageCheckCategoryType.完整性.getCode());
        error.setSeverity("warning");
        error.setDetails(new ImageCheckErrorDetails(expected, actual, null, null));
        return error;
    }

    public static ImageCheckError createPieceNumberError(int rowIndex, String columnName, String imagePath,
            String message, Integer missingPiece) {
        ImageCheckError error = new ImageCheckError(rowIndex, columnName, imagePath, "pieceContinuous",
                message);
        error.setErrorCode("PIECE_NUMBER_ERROR");
        error.setCategory(ImageCheckCategoryType.完整性.getCode());
        error.setSeverity("warning");
        error.setDetails(new ImageCheckErrorDetails(null, null, missingPiece, null));
        return error;
    }

    public static ImageCheckError createPageNumberError(int rowIndex, String columnName, String imagePath,
            String message, Integer missingPage) {
        ImageCheckError error = new ImageCheckError(rowIndex, columnName, imagePath, "continuity",
                message);
        error.setErrorCode("PAGE_NUMBER_ERROR");
        error.setCategory(ImageCheckCategoryType.完整性.getCode());
        error.setSeverity("warning");
        error.setDetails(new ImageCheckErrorDetails(null, null, missingPage, null));
        return error;
    }

    public static ImageCheckError createPdfImageUniformityError(int rowIndex, String columnName, String imagePath,
            String message, String baseName) {
        ImageCheckError error = new ImageCheckError(rowIndex, columnName, imagePath, "pdf_image_uniformity_error",
                message);
        error.setErrorCode("PDF_IMAGE_UNIFORMITY_ERROR");
        error.setImageName(baseName);
        error.setHiddenFileName(baseName);
        error.setCategory(ImageCheckCategoryType.真实性.getCode());
        error.setSeverity("error");
        return error;
    }

    public static ImageCheckError createBitDepthError(int rowIndex, String columnName, String imagePath,
            String imageName, int expectedBitDepth, int actualBitDepth) {
        ImageCheckError error = new ImageCheckError(rowIndex, columnName, imagePath, "bit_depth_error",
                String.format("位深度不足: 期望 >= %d位, 实际 %d位", expectedBitDepth, actualBitDepth));
        error.setErrorCode("BIT_DEPTH_ERROR");
        error.setImageName(imageName);
        error.setHiddenFileName(imageName);
        error.setCategory(ImageCheckCategoryType.可用性.getCode());
        error.setSeverity("error");
        error.setDetails(new ImageCheckErrorDetails(expectedBitDepth, actualBitDepth, null, null));
        return error;
    }

    public static ImageCheckError createHouseAngleError(int rowIndex, String columnName, String imagePath,
            String imageName, int skewAngle, int tolerance) {
        ImageCheckError error = new ImageCheckError(rowIndex, columnName, imagePath, "house_angle_error",
                String.format("文本方向异常: 偏转 %d° > 容差 %d°", skewAngle, tolerance));
        error.setErrorCode("HOUSE_ANGLE_ERROR");
        error.setImageName(imageName);
        error.setHiddenFileName(imageName);
        error.setCategory(ImageCheckCategoryType.完整性.getCode());
        error.setSeverity("warning");
        error.setDetails(new ImageCheckErrorDetails(null, null, skewAngle, tolerance));
        return error;
    }

    public static ImageCheckError createDamageError(int rowIndex, String columnName, String imagePath,
            String imageName, String message) {
        ImageCheckError error = new ImageCheckError(rowIndex, columnName, imagePath, "damage_error",
                message != null ? message : "文件损坏或无法读取");
        error.setErrorCode("DAMAGE_ERROR");
        error.setImageName(imageName);
        error.setHiddenFileName(imageName);
        error.setCategory(ImageCheckCategoryType.可用性.getCode());
        error.setSeverity("error");
        return error;
    }

    public static ImageCheckError createResolutionError(int rowIndex, String columnName, String imagePath,
            String imageName, int minWidth, int minHeight, int actualWidth, int actualHeight) {
        ImageCheckError error = new ImageCheckError(rowIndex, columnName, imagePath, "resolution_error",
                String.format("分辨率不足: 期望 >= %dx%d, 实际 %dx%d", minWidth, minHeight, actualWidth, actualHeight));
        error.setErrorCode("RESOLUTION_ERROR");
        error.setImageName(imageName);
        error.setHiddenFileName(imageName);
        error.setCategory(ImageCheckCategoryType.可用性.getCode());
        error.setSeverity("error");
        error.setDetails(new ImageCheckErrorDetails(String.format("%dx%d", minWidth, minHeight),
                String.format("%dx%d", actualWidth, actualHeight), null, null));
        return error;
    }

    public static class ImageCheckErrorDetails {

        @JsonProperty("expectedValue")
        private Object expectedValue;

        @JsonProperty("actualValue")
        private Object actualValue;

        @JsonProperty("threshold")
        private Object threshold;

        @JsonProperty("actual")
        private Object actual;

        public ImageCheckErrorDetails() {
        }

        public ImageCheckErrorDetails(Object expectedValue, Object actualValue, Object threshold, Object actual) {
            this.expectedValue = expectedValue;
            this.actualValue = actualValue;
            this.threshold = threshold;
            this.actual = actual;
        }

        public Object getExpectedValue() {
            return expectedValue;
        }

        public void setExpectedValue(Object expectedValue) {
            this.expectedValue = expectedValue;
        }

        public Object getActualValue() {
            return actualValue;
        }

        public void setActualValue(Object actualValue) {
            this.actualValue = actualValue;
        }

        public Object getThreshold() {
            return threshold;
        }

        public void setThreshold(Object threshold) {
            this.threshold = threshold;
        }

        public Object getActual() {
            return actual;
        }

        public void setActual(Object actual) {
            this.actual = actual;
        }
    }
}
