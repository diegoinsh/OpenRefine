/*
 * Data Quality Extension - Table View helpers
 *
 * 下沉模块只经 {@link TableView} 取数，本工具收敛两个高频派生量：
 *  - {@link #allRowIndexes(TableView)}：全行行号（替代原先直接使用 project.rows）；
 *  - {@link #columnIndexMap(TableView)}：列名 → **列位置**（与 TableView.value(row,col) 的 col 一致）。
 */
package com.google.refine.extension.quality.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jiongche.sdk.data.TableView;

public final class TableViews {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TableViews() {
    }

    /**
     * 读取扩展状态槽（OR overlay）的 JSON 串并反序列化为指定类型；
     * 槽不存在或解析失败返回 {@code null}。
     *
     * <p>下沉模块不持有 OR 类型，故 overlay 一律经 {@link TableView#overlayJson(String)}
     * 以 JSON 投影读入（写侧仍走宿主变更施加路径，状态同源）。
     */
    public static <T> T overlay(TableView view, String name, Class<T> type) {
        String json = view.overlayJson(name);
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            return null;
        }
    }

    /** 0..rowCount-1 的行号清单（替代 project.rows 全量遍历）。 */
    public static List<Integer> allRowIndexes(TableView view) {
        int n = view.rowCount();
        List<Integer> indexes = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            indexes.add(i);
        }
        return indexes;
    }

    /** 列名 → 列位置（语义 = TableView 的 col 参数；不存在则不在 map 中）。 */
    public static Map<String, Integer> columnIndexMap(TableView view) {
        List<String> names = view.schema().columnNames();
        Map<String, Integer> map = new HashMap<>();
        for (int i = 0; i < names.size(); i++) {
            map.put(names.get(i), i);
        }
        return map;
    }
}
