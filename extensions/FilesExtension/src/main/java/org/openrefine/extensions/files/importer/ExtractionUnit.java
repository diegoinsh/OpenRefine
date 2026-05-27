package org.openrefine.extensions.files.importer;

import java.util.ArrayList;
import java.util.List;

/**
 * 表示一个信息提取单元（文件夹或PDF文件）。
 * 信息提取的最小处理单位为文件夹或独立PDF文件：
 * - 文件夹内的所有图像文件属于同一份文件，整体进行OCR+要素提取
 * - 一个独立的PDF文件整体进行提取
 */
public class ExtractionUnit {

    public static final String TYPE_FOLDER = "folder";
    public static final String TYPE_PDF = "pdf";

    private String path;            // 文件夹路径或PDF文件路径
    private String type;            // "folder" 或 "pdf"
    private List<String> files;     // 文件夹内的文件列表（仅folder类型有效）
    private String name;            // 显示名称（文件夹名或PDF文件名）

    public ExtractionUnit() {
        this.files = new ArrayList<>();
    }

    public ExtractionUnit(String path, String type, String name) {
        this.path = path;
        this.type = type;
        this.name = name;
        this.files = new ArrayList<>();
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public List<String> getFiles() {
        return files;
    }

    public void setFiles(List<String> files) {
        this.files = files;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isFolder() {
        return TYPE_FOLDER.equals(type);
    }

    public boolean isPdf() {
        return TYPE_PDF.equals(type);
    }

    /**
     * 获取文件夹内的文件数量（仅folder类型有意义）
     */
    public int getFileCount() {
        return files != null ? files.size() : 0;
    }

    @Override
    public String toString() {
        return "ExtractionUnit{" +
                "path='" + path + '\'' +
                ", type='" + type + '\'' +
                ", name='" + name + '\'' +
                ", fileCount=" + getFileCount() +
                '}';
    }
}

