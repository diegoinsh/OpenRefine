package org.openrefine.extensions.files.importer;

import com.google.refine.model.*;
import com.google.refine.model.SheetData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

public class FileElementExtractor {

    private static final Logger logger = LoggerFactory.getLogger(FileElementExtractor.class);
    private final ExtractionTemplate template;
    private final ArchiveNumberConfig archiveNumberConfig;
    private final AimpLlmClient aimpClient;

    public FileElementExtractor(ExtractionTemplate template, ArchiveNumberConfig archiveNumberConfig, AimpLlmClient aimpClient) {
        this.template = template;
        this.archiveNumberConfig = archiveNumberConfig;
        this.aimpClient = aimpClient;
    }

    public SheetData extractToSheetData(List<ExtractionUnit> units) {
        SheetData sd = new SheetData("extraction-result", "信息提取", "");
        String[] cols = template.getColumns();
        for (int i = 0; i < cols.length; i++) {
            sd.columnModel.columns.add(new Column(i, cols[i]));
            sd.columnModel.setMaxCellIndex(i);
        }
        for (ExtractionUnit unit : units) {
            sd.rows.add(extractSingleUnit(unit, cols));
        }
        if (template.isGenerateVolumeSummary() && !sd.rows.isEmpty()) {
            Row vol = generateVolumeSummaryRow(sd.rows, cols, units);
            if (vol != null) sd.rows.add(0, vol);
        }
        return sd;
    }

    private Row extractSingleUnit(ExtractionUnit unit, String[] cols) {
        Row row = new Row(cols.length);
        String status = "成功";
        String archNum = archiveNumberConfig.generateArchiveNumber(unit.getPath());
        Map<String, String> ext = new HashMap<>();
        try {
            ext = doExtract(unit);
            if (ext.isEmpty()) status = "失败";
        } catch (Exception e) {
            logger.error("Extraction failed: " + unit.getPath(), e);
            status = "失败";
        }
        for (int i = 0; i < cols.length; i++) {
            String v;
            switch (cols[i]) {
                case "档号": v = archNum; break;
                case "源路径": v = unit.getPath(); break;
                case "提取状态": v = status; break;
                case "条目类型": v = "案件"; break;
                default: 
                    v = "";
                    for (java.util.Map.Entry<String, String> entry : template.getExtractionKeyMapping().entrySet()) {
                        if (cols[i].equals(entry.getValue())) {
                            v = ext.getOrDefault(entry.getKey(), "");
                            break;
                        }
                    }
                    if (v.isEmpty()) v = ext.getOrDefault(cols[i], "");
                    break;
            }
            row.setCell(i, new Cell(v, null));
        }
        return row;
    }

    private Map<String, String> doExtract(ExtractionUnit unit) {
        String keyList = String.join(",", template.getExtractionKeys());
        if (unit.isPdf()) return aimpClient.extractContent(unit.getPath(), keyList);
        if (unit.isFolder() && unit.getFiles() != null && !unit.getFiles().isEmpty())
            return aimpClient.extractContent(unit.getFiles().get(0), keyList);
        return new HashMap<>();
    }

    private Row generateVolumeSummaryRow(List<Row> rows, String[] cols, List<ExtractionUnit> units) {
        Row row = new Row(cols.length);
        int ti = template.getColumnIndex("题名");
        int ai = template.getColumnIndex("责任者");
        List<String> titles = new ArrayList<>();
        Set<String> authors = new LinkedHashSet<>();
        for (Row r : rows) {
            if (ti >= 0) {
                Object v = r.getCellValue(ti);
                if (v != null && !v.toString().isEmpty()) titles.add(v.toString());
            }
            if (ai >= 0) {
                Object v = r.getCellValue(ai);
                if (v != null && !v.toString().isEmpty()) {
                    for (String p : v.toString().split("[,，、;；]")) {
                        String t = p.trim();
                        if (!t.isEmpty()) authors.add(t);
                    }
                }
            }
        }
        String volTitle = summarizeTitles(titles);
        String volAuthors = String.join("、", authors);
        String volPath = "";
        if (!units.isEmpty()) {
            File f = new File(units.get(0).getPath());
            volPath = f.getParent() != null ? f.getParent() : units.get(0).getPath();
        }
        for (int i = 0; i < cols.length; i++) {
            String v;
            switch (cols[i]) {
                case "条目类型": v = "案卷"; break;
                case "题名": v = volTitle; break;
                case "责任者": v = volAuthors; break;
                case "源路径": v = volPath; break;
                default: v = ""; break;
            }
            row.setCell(i, new Cell(v, null));
        }
        return row;
    }

    private String summarizeTitles(List<String> titles) {
        if (titles.isEmpty()) return "";
        if (titles.size() == 1) return titles.get(0);
        try {
            StringBuilder p = new StringBuilder();
            p.append("请对以下多个档案文件的题名进行概括，生成一个简洁的案卷级题名。\n\n各案件题名列表：\n");
            for (String t : titles) p.append(t).append("\n");
            p.append("\n请直接返回概括后的题名文本，不需要其他格式。");
            AimpLlmClient.LlmAnalyzeResult r = aimpClient.llmAnalyze(p.toString(), "text");
            if (r.isSuccess() && r.getResult() != null) {
                String txt = r.getResult().asText().trim();
                if (!txt.isEmpty()) return txt;
            }
        } catch (Exception e) {
            logger.error("Error summarizing titles", e);
        }
        return titles.stream().limit(3).collect(Collectors.joining("；"));
    }
}

