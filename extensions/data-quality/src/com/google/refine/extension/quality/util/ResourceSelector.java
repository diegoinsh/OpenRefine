/*
 * Data Quality Extension - Resource Selector
 *
 * 按「件」定位资源文件：
 *  - 人工录入 + 批量导入：一行对应一个文件夹，文件夹内文件即该件的全部页；
 *  - 案卷级自动提取：同一卷的 N 件共用同一个「文件夹路径」，必须按每件的页区间切片。
 * 内容比对与资源检查共用这里的选取逻辑，保证两者对「一件包含哪些页」的理解一致。
 */
package com.google.refine.extension.quality.util;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.refine.extension.quality.util.ColumnSemantics.Slot;
import com.google.refine.model.Cell;
import com.google.refine.model.Row;

public final class ResourceSelector {

    /** 可参与比对的资源文件扩展名 */
    public static final List<String> RESOURCE_EXTENSIONS = Arrays.asList(
            ".pdf", ".jpg", ".jpeg", ".png", ".tif", ".tiff", ".bmp", ".gif", ".webp");

    /**
     * 与 FilesExtension 的 UnitScanner.NATURAL_ORDER 等价的自然序。
     * 按件切页区间时必须与提取阶段生成「起止页号」时的排序一致，否则会切错页。
     */
    public static final Comparator<String> NATURAL_ORDER = (a, b) -> {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i), cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int si = i, sj = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) i++;
                while (j < b.length() && Character.isDigit(b.charAt(j))) j++;
                String na = a.substring(si, i).replaceAll("^0+", "");
                String nb = b.substring(sj, j).replaceAll("^0+", "");
                int cmp = na.length() != nb.length()
                        ? Integer.compare(na.length(), nb.length())
                        : na.compareTo(nb);
                if (cmp != 0) return cmp;
            } else {
                int cmp = Character.compare(ca, cb);
                if (cmp != 0) return cmp;
                i++;
                j++;
            }
        }
        return Integer.compare(a.length() - i, b.length() - j);
    };

    private ResourceSelector() {
    }

    /** 读取单元格字符串值，列不存在或为空时返回空串 */
    public static String cellValue(Row row, Map<String, Integer> columnIndexMap, String columnName) {
        if (columnName == null) return "";
        Integer cellIndex = columnIndexMap.get(columnName);
        if (cellIndex == null) return "";
        Cell cell = row.getCell(cellIndex);
        return cell != null && cell.value != null ? cell.value.toString().trim() : "";
    }

    /** 解析整数；形如 0001-0005 的区间串取起始值 */
    public static Integer parseInt(String text) {
        if (text == null || text.trim().isEmpty()) return null;
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            int[] parsed = ColumnSemantics.parsePageRange(text);
            return parsed == null ? null : parsed[0];
        }
    }

    /**
     * 解析本件的页区间（卷内 1-based 全局页序号，含端点）。
     * 依次尝试：起止页号 → 起始页号+终止页号 → 起始页号+页数；无页号信息返回 null（整件＝整目录）。
     */
    public static int[] resolvePageRange(Row row, Map<String, Integer> columnIndexMap,
                                         String rangeColumn, String startColumn,
                                         String endColumn, String countColumn) {
        String rangeText = cellValue(row, columnIndexMap, rangeColumn);
        if (!rangeText.isEmpty()) {
            int[] parsed = ColumnSemantics.parsePageRange(rangeText);
            if (parsed != null) return parsed;
        }

        Integer start = parseInt(cellValue(row, columnIndexMap, startColumn));
        if (start == null) return null;

        Integer end = parseInt(cellValue(row, columnIndexMap, endColumn));
        if (end == null) {
            Integer count = parseInt(cellValue(row, columnIndexMap, countColumn));
            end = (count == null || count < 1) ? start : start + count - 1;
        }
        return new int[]{Math.min(start, end), Math.max(start, end)};
    }

    /** 按语义槽位一次性解析出页区间/具体文件所需的列名，避免各处重复查找 */
    public static PageColumns resolvePageColumns(List<String> columnNames) {
        PageColumns cols = new PageColumns();
        cols.range = ColumnSemantics.findColumnNameBySlot(Slot.PAGE_RANGE, columnNames);
        cols.start = ColumnSemantics.findColumnNameBySlot(Slot.START_PAGE, columnNames);
        cols.end = ColumnSemantics.findColumnNameBySlot(Slot.END_PAGE, columnNames);
        cols.count = ColumnSemantics.findColumnNameBySlot(Slot.PAGE_COUNT, columnNames);
        cols.file = ColumnSemantics.findColumnNameBySlot(Slot.RESOURCE_FILE, columnNames);
        return cols;
    }

    /** 页区间相关列名集合 */
    public static class PageColumns {
        public String range;
        public String start;
        public String end;
        public String count;
        public String file;

        public int[] resolveRange(Row row, Map<String, Integer> columnIndexMap) {
            return resolvePageRange(row, columnIndexMap, range, start, end, count);
        }
    }

    /** 列出文件夹内可作为资源的文件，按与提取阶段一致的自然序排序 */
    public static List<File> listResourceFiles(File folder) {
        File[] files = folder.listFiles((dir, name) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            for (String ext : RESOURCE_EXTENSIONS) {
                if (lower.endsWith(ext)) return true;
            }
            return false;
        });
        if (files == null || files.length == 0) return new ArrayList<>();
        List<File> sorted = new ArrayList<>(Arrays.asList(files));
        sorted.sort((f1, f2) -> NATURAL_ORDER.compare(f1.getName(), f2.getName()));
        return sorted;
    }

    /** 若路径本身是文件，或「目录 + 文件名」命中，则返回该具体文件路径 */
    public static String resolveExplicitFile(String resourcePath, String fileName) {
        File direct = new File(resourcePath);
        if (direct.isFile()) {
            return direct.getAbsolutePath();
        }
        if (fileName == null || fileName.trim().isEmpty()) {
            return null;
        }
        File candidate = new File(direct, fileName.trim());
        return candidate.isFile() ? candidate.getAbsolutePath() : null;
    }

    /**
     * 返回本件对应的资源文件清单。
     *
     * @param resourcePath 行内的资源路径（目录或文件）
     * @param fileName     行内「文件名」列的值，可为空
     * @param pageRange    本件页区间（1-based 含端点），可为 null 表示整目录
     * @return 文件清单；路径无效或页区间超出目录范围时返回 null
     */
    public static List<File> resolvePieceFiles(String resourcePath, String fileName, int[] pageRange) {
        if (resourcePath == null || resourcePath.isEmpty()) return null;

        String explicitFile = resolveExplicitFile(resourcePath, fileName);
        if (explicitFile != null) {
            List<File> single = new ArrayList<>();
            single.add(new File(explicitFile));
            return single;
        }

        File folder = new File(resourcePath);
        if (!folder.exists() || !folder.isDirectory()) return null;

        List<File> files = listResourceFiles(folder);
        if (files.isEmpty()) return null;
        if (pageRange == null) return files;

        if (pageRange[0] < 1 || pageRange[0] > files.size()) {
            return null;
        }
        int to = Math.min(pageRange[1], files.size());
        return new ArrayList<>(files.subList(pageRange[0] - 1, to));
    }
}