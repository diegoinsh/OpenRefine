package com.google.refine.extension.records.db;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 流式结果集处理器
 * 
 * 用于处理大数据量查询结果，避免一次性加载所有数据到内存
 * 支持分批处理和迭代访问
 */
public class StreamingResultSet implements Iterator<ObjectNode> {
    private static final Logger logger = LoggerFactory.getLogger(StreamingResultSet.class);
    
    private final ResultSet resultSet;
    private final List<String> columnNames;
    private final int batchSize;
    private ObjectNode nextRow;
    private boolean hasNextRow;
    private long rowCount;
    private boolean closed;
    
    /**
     * 构造函数
     * 
     * @param resultSet 数据库结果集
     * @param columnNames 列名列表
     * @param batchSize 批处理大小
     */
    public StreamingResultSet(ResultSet resultSet, List<String> columnNames, int batchSize) {
        this.resultSet = resultSet;
        this.columnNames = columnNames;
        this.batchSize = batchSize > 0 ? batchSize : 1000;
        this.rowCount = 0;
        this.closed = false;
        this.hasNextRow = false;
        
        // 预加载第一行
        loadNextRow();
    }
    
    /**
     * 构造函数 (默认批处理大小 1000)
     */
    public StreamingResultSet(ResultSet resultSet, List<String> columnNames) {
        this(resultSet, columnNames, 1000);
    }
    
    /**
     * 检查是否有下一行
     */
    @Override
    public boolean hasNext() {
        if (closed) {
            return false;
        }
        return hasNextRow;
    }
    
    /**
     * 获取下一行
     */
    @Override
    public ObjectNode next() {
        if (!hasNext()) {
            throw new NoSuchElementException("No more rows");
        }
        
        ObjectNode currentRow = nextRow;
        rowCount++;
        
        // 加载下一行
        loadNextRow();
        
        return currentRow;
    }
    
    /**
     * 加载下一行
     */
    private void loadNextRow() {
        try {
            if (resultSet.next()) {
                nextRow = convertRowToObject();
                hasNextRow = true;
            } else {
                hasNextRow = false;
                close();
            }
        } catch (SQLException e) {
            logger.error("Error loading next row", e);
            hasNextRow = false;
            close();
        }
    }
    
    /**
     * 将数据库行转换为 ObjectNode
     */
    private ObjectNode convertRowToObject() throws SQLException {
        ObjectNode row = com.google.refine.util.ParsingUtilities.mapper.createObjectNode();
        
        for (String columnName : columnNames) {
            Object value = resultSet.getObject(columnName);
            
            if (value == null) {
                row.putNull(columnName);
            } else if (value instanceof String) {
                row.put(columnName, (String) value);
            } else if (value instanceof Integer) {
                row.put(columnName, (Integer) value);
            } else if (value instanceof Long) {
                row.put(columnName, (Long) value);
            } else if (value instanceof Double) {
                row.put(columnName, (Double) value);
            } else if (value instanceof Boolean) {
                row.put(columnName, (Boolean) value);
            } else if (value instanceof java.sql.Timestamp) {
                row.put(columnName, ((java.sql.Timestamp) value).getTime());
            } else if (value instanceof java.sql.Date) {
                row.put(columnName, ((java.sql.Date) value).getTime());
            } else {
                row.put(columnName, value.toString());
            }
        }
        
        return row;
    }
    
    /**
     * 获取已处理的行数
     */
    public long getRowCount() {
        return rowCount;
    }
    
    /**
     * 获取批处理大小
     */
    public int getBatchSize() {
        return batchSize;
    }
    
    /**
     * 关闭结果集
     */
    public void close() {
        if (!closed) {
            try {
                if (resultSet != null && !resultSet.isClosed()) {
                    resultSet.close();
                }
            } catch (SQLException e) {
                logger.warn("Error closing result set", e);
            }
            closed = true;
        }
    }
    
    /**
     * 检查是否已关闭
     */
    public boolean isClosed() {
        return closed;
    }
    
    /**
     * 获取批次数据
     * 
     * @return 包含最多 batchSize 行的列表
     */
    public List<ObjectNode> nextBatch() {
        List<ObjectNode> batch = new ArrayList<>();
        
        for (int i = 0; i < batchSize && hasNext(); i++) {
            batch.add(next());
        }
        
        return batch;
    }
    
    /**
     * 获取所有剩余数据
     * 
     * @return 包含所有剩余行的列表
     */
    public List<ObjectNode> getAll() {
        List<ObjectNode> all = new ArrayList<>();
        
        while (hasNext()) {
            all.add(next());
        }
        
        return all;
    }
}

