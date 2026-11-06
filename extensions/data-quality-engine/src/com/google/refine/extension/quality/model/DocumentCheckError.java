/*
 * Data Quality Extension - Document Check Error
 * 文书级（案件质量评查）检测结论：裁判文书是否按规范盖章
 */
package com.google.refine.extension.quality.model;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 单件文书的检测结论。
 *
 * <p>与条目级 {@link CheckResult.CheckError} 并列：条目级看「著录对不对」，
 * 文书级看「这份裁判文书盖没盖章」。同一件可同时有必填要素缺失与印章缺失，
 * 故 {@link #errorType} 区分 {@code seal_missing} / {@code seal_insufficient} / {@code field_missing}。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DocumentCheckError {

    /** 行号（项目数据行，从 0 起）。 */
    @JsonProperty("rowIndex")
    private int rowIndex;

    @JsonProperty("archiveNo")
    private String archiveNo;

    @JsonProperty("pieceNo")
    private String pieceNo;

    @JsonProperty("documentTitle")
    private String documentTitle;

    /** 命中的文书类型编码（如 civil_judgment）。 */
    @JsonProperty("documentType")
    private String documentType;

    /** 命中文书类型名称（如 民事判决书）。 */
    @JsonProperty("documentTypeName")
    private String documentTypeName;

    @JsonProperty("errorType")
    private String errorType;

    /** {@code errorType=field_missing} 时缺失的要素列名。 */
    @JsonProperty("missingField")
    private String missingField;

    @JsonProperty("message")
    private String message;

    @JsonProperty("actualSealCount")
    private Integer actualSealCount;

    @JsonProperty("requiredSealCount")
    private Integer requiredSealCount;

    /** 实际参与检测的图像文件名。 */
    @JsonProperty("checkedImages")
    private List<String> checkedImages = new ArrayList<>();

    public DocumentCheckError() {}

    public int getRowIndex() { return rowIndex; }
    public void setRowIndex(int rowIndex) { this.rowIndex = rowIndex; }

    public String getArchiveNo() { return archiveNo; }
    public void setArchiveNo(String archiveNo) { this.archiveNo = archiveNo; }

    public String getPieceNo() { return pieceNo; }
    public void setPieceNo(String pieceNo) { this.pieceNo = pieceNo; }

    public String getDocumentTitle() { return documentTitle; }
    public void setDocumentTitle(String documentTitle) { this.documentTitle = documentTitle; }

    public String getDocumentType() { return documentType; }
    public void setDocumentType(String documentType) { this.documentType = documentType; }

    public String getDocumentTypeName() { return documentTypeName; }
    public void setDocumentTypeName(String documentTypeName) { this.documentTypeName = documentTypeName; }

    public String getErrorType() { return errorType; }
    public void setErrorType(String errorType) { this.errorType = errorType; }

    public String getMissingField() { return missingField; }
    public void setMissingField(String missingField) { this.missingField = missingField; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public Integer getActualSealCount() { return actualSealCount; }
    public void setActualSealCount(Integer actualSealCount) { this.actualSealCount = actualSealCount; }

    public Integer getRequiredSealCount() { return requiredSealCount; }
    public void setRequiredSealCount(Integer requiredSealCount) { this.requiredSealCount = requiredSealCount; }

    public List<String> getCheckedImages() { return checkedImages; }
    public void setCheckedImages(List<String> checkedImages) { this.checkedImages = checkedImages; }
}
