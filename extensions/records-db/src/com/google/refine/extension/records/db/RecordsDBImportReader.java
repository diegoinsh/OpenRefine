package com.google.refine.extension.records.db;

import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.refine.extension.records.db.model.SchemaProfile;
import com.google.refine.importers.TabularImportingParserBase.TableDataReader;
import com.google.refine.importing.ImportingJob;

/**
 * TableDataReader implementation for records-db extension.
 */
public class RecordsDBImportReader implements TableDataReader {

    private static final Logger logger = LoggerFactory.getLogger("RecordsDBImportReader");

    private final Connection conn;
    private final SchemaProfile profile;
    private final ImportingJob job;
    private final int batchSize;
    private final int maxRows;

    private boolean usedHeader = false;
    private int offset = 0;
    private boolean lastBatch = false;
    private List<List<Object>> rows = null;
    private int rowIndex = 0;
    private List<String> columnLabels = null;

    public RecordsDBImportReader(Connection conn, SchemaProfile profile,
                                 ImportingJob job, int batchSize, int maxRows) {
        this.conn = conn;
        this.profile = profile;
        this.job = job;
        this.batchSize = batchSize > 0 ? batchSize : 1000;
        this.maxRows = maxRows > 0 ? maxRows : -1;
    }

    @Override
    public List<Object> getNextRowOfCells() throws IOException {
        if (job != null && job.canceled) {
            return null;
        }

        if (!usedHeader) {
            loadNextBatchIfNeeded();
            usedHeader = true;
            if (columnLabels == null || columnLabels.isEmpty()) {
                return null;
            }
            return new ArrayList<Object>(columnLabels);
        }

        if (rows == null || rowIndex >= rows.size()) {
            loadNextBatchIfNeeded();
            if (rows == null || rows.isEmpty()) {
                return null;
            }
        }

        return rows.get(rowIndex++);
    }

    private void loadNextBatchIfNeeded() throws IOException {
        if (lastBatch) {
            rows = null;
            return;
        }
        if (maxRows > 0 && offset >= maxRows) {
            lastBatch = true;
            rows = null;
            return;
        }

        int limit = batchSize;
        if (maxRows > 0) {
            int remaining = maxRows - offset;
            if (remaining <= 0) {
                lastBatch = true;
                rows = null;
                return;
            }
            if (remaining < limit) {
                limit = remaining;
            }
        }

        String query = QueryBuilder.buildSelectQueryWithPagination(profile, offset, limit);
        if (logger.isDebugEnabled()) {
            logger.debug("Import query (offset={}, limit={}): {}", offset, limit, query);
        }

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(query)) {

            ResultSetMetaData md = rs.getMetaData();
            int colCount = md.getColumnCount();

            if (columnLabels == null) {
                columnLabels = new ArrayList<String>(colCount);
                for (int i = 1; i <= colCount; i++) {
                    String label = null;
                    try { label = md.getColumnLabel(i); } catch (Exception ignore) {}
                    if (label == null || label.isEmpty()) {
                        try { label = md.getColumnName(i); } catch (Exception ignore) {}
                    }
                    columnLabels.add(label);
                }
            }

            rows = new ArrayList<List<Object>>();
            rowIndex = 0;
            while (rs.next()) {
                List<Object> row = new ArrayList<Object>(colCount);
                for (int i = 1; i <= colCount; i++) {
                    Object value = rs.getObject(i);
                    row.add(convertValue(value));
                }
                rows.add(row);
            }
            offset += rows.size();
            if (rows.isEmpty() || rows.size() < limit) {
                lastBatch = true;
            }
        } catch (Exception e) {
            logger.error("Error loading batch for records-db import", e);
            throw new IOException(e);
        }
    }

    private Object convertValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        return value.toString();
    }
}

