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

    public static class Volume {
        public String name;
        public String path;
        public boolean pdfMode;
        public boolean mixedContent;
        public List<String> pages = new ArrayList<>();
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
        File[] files = dir.listFiles(File::isFile);
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
            v.mixedContent = true;
            return v;
        }
        if (!pdfs.isEmpty()) {
            v.pdfMode = true;
            pdfs.sort((p1, p2) -> NATURAL_ORDER.compare(new File(p1).getName(), new File(p2).getName()));
            v.pages.addAll(pdfs);
        } else {
            images.sort((p1, p2) -> NATURAL_ORDER.compare(new File(p1).getName(), new File(p2).getName()));
            v.pages.addAll(images);
        }
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
