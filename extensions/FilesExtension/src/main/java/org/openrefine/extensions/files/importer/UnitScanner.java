package org.openrefine.extensions.files.importer;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class UnitScanner {

    public static final Set<String> IMAGE_EXTENSIONS = new HashSet<>(Arrays.asList(
            "tif", "tiff", "jpg", "jpeg", "png", "bmp", "gif"));
    public static final Set<String> PDF_EXTENSIONS = new HashSet<>(Collections.singletonList("pdf"));

    // 数字感知的自然排序：数字段按数值比较，避免无补零文件名"10"按字典序排在"2"前面
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

    /**
     * 从文件名解析「卷内页号」。
     *
     * 投放命名规范：卷宗封面、卷内目录等卷前材料编为 0000-NN（不计卷内页号），
     * 诉讼文书材料自 0001 起连续编号。程序必须按文件名取页号，不能用列表下标——
     * 列表里还含封面、目录、备考表等不计页材料，用下标会让全卷页号整体偏移。
     * 实测同一套渲染规则下，含封面的 JZ07-2024-M2-0241 偏移 4、无封面的 JZ07-2024-M2-0158 偏移 0，
     * 因此页号错位随卷而异，不能靠固定偏移量修正。
     *
     * @return 卷内页号；卷前材料（0000-NN）或文件名不含 4 位以上编号时返回 null（调用方回退为列表下标）
     */
    public static Integer parseInternalPageNo(String filePath) {
        if (filePath == null) return null;
        String name = new File(filePath).getName();
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        if (name.matches(".*\\d{4}-\\d{2}$")) return null;   // 卷前材料：封面、卷内目录
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{4,})$").matcher(name);
        if (!m.find()) return null;
        try {
            int no = Integer.parseInt(m.group(1));
            return no > 0 ? no : null;   // 0000.jpg 一类无区分度的编号不作页号
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static class Volume {
        public String name;
        public String path;
        public boolean pdfMode;
        public boolean mixedContent;
        public List<String> pages = new ArrayList<>();
        /** 与 pages 平行的「卷内页号」；卷前不计页材料或无法解析的文件名为 null */
        public List<Integer> pageNos = new ArrayList<>();
        /** 本卷是否至少有一页解析出卷内页号：为 false 时全部按列表下标处理（普通命名目录） */
        public boolean numberedPages;
    }

    public static List<Volume> scanVolumes(String rootPath) {
        List<Volume> volumes = new ArrayList<>();
        File root = new File(rootPath);
        File[] dirs = root.listFiles(File::isDirectory);
        if (dirs == null) return volumes;
        Arrays.sort(dirs, Comparator.comparing(File::getName, NATURAL_ORDER));
        for (File dir : dirs) {
            Volume v = scanDirectory(dir);
            if (v != null && !v.mixedContent) {
                volumes.add(v);
            }
        }
        return volumes;
    }

    public static List<Volume> scanCases(String rootPath) {
        List<Volume> cases = new ArrayList<>();
        File root = new File(rootPath);
        File[] entries = root.listFiles();
        if (entries == null) return cases;
        List<File> dirs = new ArrayList<>();
        List<File> pdfFiles = new ArrayList<>();
        for (File f : entries) {
            if (f.isDirectory()) {
                dirs.add(f);
            } else if (f.isFile() && PDF_EXTENSIONS.contains(ext(f.getName()))) {
                pdfFiles.add(f);
            }
        }
        dirs.sort(Comparator.comparing(File::getName, NATURAL_ORDER));
        pdfFiles.sort(Comparator.comparing(File::getName, NATURAL_ORDER));
        for (File dir : dirs) {
            Volume v = scanDirectory(dir);
            if (v != null && !v.mixedContent) {
                cases.add(v);
            }
        }
        for (File pdf : pdfFiles) {
            Volume v = new Volume();
            v.name = stripExtension(pdf.getName());
            v.path = pdf.getAbsolutePath();
            v.pdfMode = true;
            v.pages.add(pdf.getAbsolutePath());
            cases.add(v);
        }
        return cases;
    }

    private static Volume scanDirectory(File dir) {
        File[] files = dir.listFiles(f -> f.isFile() && !f.getName().startsWith("."));
        if (files == null) return null;
        List<String> images = new ArrayList<>();
        List<String> pdfs = new ArrayList<>();
        for (File f : files) {
            String e = ext(f.getName());
            if (IMAGE_EXTENSIONS.contains(e)) {
                images.add(f.getAbsolutePath());
            } else if (PDF_EXTENSIONS.contains(e)) {
                pdfs.add(f.getAbsolutePath());
            }
        }
        if (images.isEmpty() && pdfs.isEmpty()) return null;
        Volume v = new Volume();
        v.name = dir.getName();
        v.path = dir.getAbsolutePath();
        if (!images.isEmpty() && !pdfs.isEmpty()) {
            // TODO 同一卷内同时存在图片与PDF时整卷被标记为 mixedContent 并跳过，
            // scanVolumes/scanCases 均会丢弃该卷。法院卷宗等其他场景需要支持混合内容，
            // 本次不实现。
            v.mixedContent = true;
            return v;
        }
        if (!pdfs.isEmpty()) {
            v.pdfMode = true;
            pdfs.sort((p1, p2) -> NATURAL_ORDER.compare(new File(p1).getName(), new File(p2).getName()));
            v.pages.addAll(pdfs);
            // 整份 PDF 无法逐页解析文件名，页号全部回退为列表下标
            for (int i = 0; i < pdfs.size(); i++) v.pageNos.add(null);
        } else {
            images.sort((p1, p2) -> NATURAL_ORDER.compare(new File(p1).getName(), new File(p2).getName()));
            v.pages.addAll(images);
            for (String p : images) v.pageNos.add(parseInternalPageNo(p));
        }
        v.numberedPages = v.pageNos.stream().anyMatch(java.util.Objects::nonNull);
        return v;
    }

    private static String ext(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) return "";
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }
}
