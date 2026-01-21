# OpenRefine多Sheet数据持久化技术方案

## 1. 当前存储格式深度分析

### 1.1 项目存储结构

OpenRefine使用基于文本的存储格式，项目数据保存在`{workspace}/{projectID}.project/`目录下。

#### 1.1.1 主项目文件格式（project.txt）

**文件结构**：
```
{RefineServlet.VERSION}
columnModel=
{ColumnModel数据}
history=
{History数据}
overlayModel:{modelName}=
{OverlayModel数据}
rowCount=
{行数}
{每行数据}
```

**详细格式**：

1. **版本号**（第1行）
   - 格式：`{version}`
   - 示例：`3.11`

2. **列模型**（columnModel=）
   - 格式：`columnModel=\n{ColumnModel.save()输出}\n/e/\n`
   - ColumnModel.save()输出：
     ```
     maxCellIndex={最大单元格索引}
     keyColumnIndex={关键列索引}
     columnCount={列数}
     {每列JSON数据}
     columnGroupCount={列组数}
     {每列组数据}
     /e/
     ```

3. **历史记录**（history=）
   - 格式：`history=\n{History.save()输出}\n/e/\n`
   - History.save()输出：
     ```
     pastEntryCount={已完成条目数}
     {每条HistoryEntry的JSON数据}
     futureEntryCount={未完成条目数}
     {每条HistoryEntry的JSON数据}
     /e/
     ```

4. **扩展模型**（overlayModel:{modelName}=）
   - 格式：`overlayModel:{modelName}=\n{JSON数据}\n`
   - 可选，可能有多个

5. **行数据**（rowCount=）
   - 格式：`rowCount={行数}\n{每行JSON数据}\n`
   - 每行JSON数据包含cells数组、flagged、starred等字段

#### 1.1.2 历史条目文件格式

**存储位置**：`{projectID}.project/history/{entryID}.change.zip`

**ZIP文件结构**：
```
change.txt    - Change对象的序列化数据
pool.txt      - Pool对象（用于共享数据）
```

**change.txt格式**：
```
{RefineServlet.VERSION}
{Change类的完整类名}
{Change.save()输出}
```

**HistoryEntry元数据**（存储在project.txt中）：
```json
{
  "id": 1234567890,
  "description": "Text transform on cells",
  "time": "2024-01-20T10:30:00Z",
  "operation": {
    "operationId": "core/text-transform",
    "columnName": "Column1",
    "expression": "value.toUppercase()"
  }
}
```

### 1.2 存储机制特点

#### 1.2.1 文本格式优势
- 可读性强，便于调试
- 版本控制友好
- 易于迁移和转换

#### 1.2.2 存储机制
- **增量保存**：只保存变更部分
- **延迟加载**：Change对象按需加载
- **压缩存储**：历史条目使用ZIP压缩

#### 1.2.3 数据一致性保证
- 使用项目级锁（Project.synchronized）
- 原子操作：保存时要么全部成功，要么全部失败
- 自动保存机制：定期保存项目状态

## 2. 多Sheet存储格式设计

### 2.1 设计原则

1. **向后兼容**：单sheet项目格式保持不变
2. **独立性**：每个sheet的数据完全独立
3. **可扩展性**：支持未来功能扩展
4. **性能优化**：减少不必要的I/O操作

### 2.2 存储格式方案

#### 2.2.1 方案A：扩展主文件格式（推荐）

**优点**：
- 所有数据集中管理
- 便于备份和迁移
- 保持现有存储结构

**缺点**：
- 大文件可能影响加载性能
- 并发写入需要更复杂的锁机制

**格式设计**：

```
{RefineServlet.VERSION}
isMultiSheetProject={true|false}
activeSheetId={sheetId}
sheetCount={sheet数量}
{每个SheetData的完整数据}
/e/
```

**SheetData格式**：
```
sheetData:{sheetId}=
{Sheet元数据JSON}
columnModel=
{ColumnModel数据}
rowCount=
{行数据}
/e/
```

**Sheet元数据JSON**：
```json
{
  "sheetId": "file.xlsx#Sheet1",
  "sheetName": "Sheet1",
  "sourceFileName": "file.xlsx",
  "activeRowIndex": 0,
  "rowCount": 1000,
  "columnCount": 5
}
```

**完整示例**：
```
3.11
isMultiSheetProject=true
activeSheetId=file.xlsx#Sheet1
sheetCount=2
sheetData:file.xlsx#Sheet1=
{"sheetId":"file.xlsx#Sheet1","sheetName":"Sheet1","sourceFileName":"file.xlsx","activeRowIndex":0,"rowCount":1000,"columnCount":5}
columnModel=
maxCellIndex=4
keyColumnIndex=0
columnCount=5
{"name":"Column1","cellIndex":0,"type":"text"}
{"name":"Column2","cellIndex":1,"type":"number"}
{"name":"Column3","cellIndex":2,"type":"text"}
{"name":"Column4","cellIndex":3,"type":"text"}
{"name":"Column5","cellIndex":4,"type":"text"}
columnGroupCount=0
/e/
rowCount=1000
{"cells":[{"v":"value1","r":null},...],"flagged":false,"starred":false}
{"cells":[{"v":"value2","r":null},...],"flagged":false,"starred":false}
...
/e/
sheetData:file.xlsx#Sheet2=
{"sheetId":"file.xlsx#Sheet2","sheetName":"Sheet2","sourceFileName":"file.xlsx","activeRowIndex":0,"rowCount":500,"columnCount":3}
columnModel=
maxCellIndex=2
keyColumnIndex=0
columnCount=3
{"name":"A","cellIndex":0,"type":"text"}
{"name":"B","cellIndex":1,"type":"number"}
{"name":"C","cellIndex":2,"type":"text"}
columnGroupCount=0
/e/
rowCount=500
{"cells":[{"v":"data1","r":null},...],"flagged":false,"starred":false}
{"cells":[{"v":"data2","r":null},...],"flagged":false,"starred":false}
...
/e/
```

#### 2.2.2 方案B：分离文件存储

**优点**：
- 每个sheet独立文件，便于并行操作
- 加载性能更好（按需加载）
- 文件大小可控

**缺点**：
- 文件管理复杂
- 备份和迁移需要处理多个文件
- 需要额外的索引文件

**文件结构**：
```
{projectID}.project/
  ├── project.txt              # 项目元数据和sheet索引
  ├── history/                 # 历史记录（共享）
  │   ├── {entryID}.change.zip
  │   └── ...
  └── sheets/                  # Sheet数据（分离）
      ├── {sheetId}.txt        # Sheet1数据
      ├── {sheetId}.txt        # Sheet2数据
      └── ...
```

**project.txt格式**：
```
{RefineServlet.VERSION}
isMultiSheetProject=true
activeSheetId={sheetId}
sheetCount={sheet数量}
{每个Sheet的索引信息}
/e/
```

**Sheet索引信息**：
```
sheet:{sheetId}=
{Sheet元数据JSON}
```

**Sheet数据文件格式**（sheets/{sheetId}.txt）：
```
columnModel=
{ColumnModel数据}
rowCount=
{行数据}
/e/
```

#### 2.2.3 方案C：混合存储（最优方案）

**设计思路**：
- 项目元数据存储在project.txt
- 每个sheet的完整数据存储在独立文件
- 历史记录保持共享，但增加sheetId字段

**文件结构**：
```
{projectID}.project/
  ├── project.txt              # 项目元数据
  ├── history/                 # 历史记录（共享）
  │   ├── {entryID}.change.zip
  │   └── ...
  └── sheets/                  # Sheet数据（分离）
      ├── {sheetId}.data.txt   # Sheet完整数据
      └── {sheetId}.history.txt # Sheet历史索引
```

**project.txt格式**：
```
{RefineServlet.VERSION}
isMultiSheetProject=true
activeSheetId={sheetId}
sheetCount={sheet数量}
{每个Sheet的索引信息}
history=
{History数据}
/e/
```

**Sheet索引信息**：
```
sheet:{sheetId}=
{Sheet元数据JSON}
```

**Sheet数据文件格式**（sheets/{sheetId}.data.txt）：
```
columnModel=
{ColumnModel数据}
rowCount=
{行数据}
/e/
```

### 2.3 历史记录存储设计

#### 2.3.1 历史记录共享方案

**设计思路**：
- 所有sheet共享同一个History对象
- 每个HistoryEntry增加sheetId字段
- 撤销/重做时根据sheetId过滤操作

**HistoryEntry扩展**：
```json
{
  "id": 1234567890,
  "sheetId": "file.xlsx#Sheet1",
  "description": "Text transform on cells",
  "time": "2024-01-20T10:30:00Z",
  "operation": {
    "operationId": "core/text-transform",
    "columnName": "Column1",
    "expression": "value.toUppercase()"
  }
}
```

**History管理**：
```java
public class History {
    protected List<HistoryEntry> _pastEntries;
    protected List<HistoryEntry> _futureEntries;
    
    public List<HistoryEntry> getPastEntries(String sheetId) {
        return _pastEntries.stream()
            .filter(entry -> entry.sheetId.equals(sheetId))
            .collect(Collectors.toList());
    }
    
    public List<HistoryEntry> getFutureEntries(String sheetId) {
        return _futureEntries.stream()
            .filter(entry -> entry.sheetId.equals(sheetId))
            .collect(Collectors.toList());
    }
}
```

#### 2.3.2 历史记录分离方案

**设计思路**：
- 每个sheet维护独立的History对象
- HistoryEntryManager支持按sheetId路由

**文件结构**：
```
{projectID}.project/
  ├── project.txt
  ├── history/
  │   ├── {sheetId}/
  │   │   ├── {entryID}.change.zip
  │   │   └── history.txt
  │   └── ...
  └── sheets/
      └── {sheetId}.data.txt
```

**History管理**：
```java
public class SheetHistory {
    protected String sheetId;
    protected List<HistoryEntry> _pastEntries;
    protected List<HistoryEntry> _futureEntries;
    
    public SheetHistory(String sheetId) {
        this.sheetId = sheetId;
        this._pastEntries = new ArrayList<>();
        this._futureEntries = new ArrayList<>();
    }
}

public class Project {
    protected Map<String, SheetHistory> sheetHistories;
    
    public SheetHistory getSheetHistory(String sheetId) {
        return sheetHistories.get(sheetId);
    }
}
```

## 3. 技术难点与解决方案

### 3.1 数据一致性

#### 3.1.1 难点：并发写入冲突

**问题描述**：
- 多个sheet同时进行操作可能导致数据不一致
- 历史记录的保存和加载可能出现竞态条件

**解决方案**：

**方案1：项目级锁（简单但影响性能）**
```java
public class Project {
    public synchronized void save() {
        // 整个项目保存时加锁
        saveToOutputStream(...);
    }
    
    public synchronized void applyOperation(Operation operation) {
        // 应用操作时加锁
        operation.apply(this);
    }
}
```

**方案2：Sheet级锁（推荐）**
```java
public class Project {
    protected Map<String, Object> sheetLocks = new ConcurrentHashMap<>();
    
    public Object getSheetLock(String sheetId) {
        return sheetLocks.computeIfAbsent(sheetId, k -> new Object());
    }
    
    public void saveSheet(String sheetId) {
        synchronized (getSheetLock(sheetId)) {
            // 只锁定当前sheet
            saveSheetData(sheetId);
        }
    }
}
```

**方案3：乐观锁（最优但复杂）**
```java
public class SheetData {
    protected volatile long version;
    
    public boolean applyOperation(Operation operation) {
        long currentVersion = version;
        if (operation.apply(this)) {
            version = currentVersion + 1;
            return true;
        }
        return false;
    }
}
```

#### 3.1.2 难点：事务性保证

**问题描述**：
- 多个sheet的操作需要保证原子性
- 部分成功部分失败会导致数据不一致

**解决方案**：

**实现两阶段提交协议**：
```java
public class MultiSheetOperation {
    protected List<Operation> operations;
    protected List<String> sheetIds;
    
    public void apply(Project project) {
        // 阶段1：准备
        List<SheetData> preparedSheets = new ArrayList<>();
        for (String sheetId : sheetIds) {
            SheetData sheet = project.getSheetData(sheetId);
            if (sheet.prepareOperation(operations)) {
                preparedSheets.add(sheet);
            } else {
                // 回滚已准备的sheet
                for (SheetData prepared : preparedSheets) {
                    prepared.rollback();
                }
                throw new OperationException("Operation preparation failed");
            }
        }
        
        // 阶段2：提交
        for (SheetData sheet : preparedSheets) {
            sheet.commitOperation();
        }
    }
}
```

### 3.2 性能优化

#### 3.2.1 难点：大文件加载性能

**问题描述**：
- 多sheet项目文件可能很大
- 加载时间过长影响用户体验

**解决方案**：

**方案1：延迟加载**
```java
public class Project {
    protected Map<String, SheetData> sheetDataMap;
    protected Map<String, Boolean> loadedSheets;
    
    public SheetData getSheetData(String sheetId) {
        if (!loadedSheets.getOrDefault(sheetId, false)) {
            loadSheetData(sheetId);
            loadedSheets.put(sheetId, true);
        }
        return sheetDataMap.get(sheetId);
    }
    
    private void loadSheetData(String sheetId) {
        // 只加载需要的sheet数据
        SheetData sheetData = loadSheetFromFile(sheetId);
        sheetDataMap.put(sheetId, sheetData);
    }
}
```

**方案2：分块加载**
```java
public class SheetData {
    protected List<Row> rows;
    protected int loadedRowCount;
    protected int totalRowCount;
    
    public List<Row> getRows(int start, int limit) {
        // 按需加载行数据
        if (start + limit > loadedRowCount) {
            loadMoreRows(start + limit);
        }
        return rows.subList(start, Math.min(start + limit, rows.size()));
    }
}
```

**方案3：缓存优化**
```java
public class SheetDataCache {
    protected Map<String, SheetData> cache;
    protected int maxSize;
    protected LinkedHashMap<String, SheetData> lruCache;
    
    public SheetData get(String sheetId) {
        SheetData sheet = cache.get(sheetId);
        if (sheet == null) {
            sheet = loadSheetData(sheetId);
            cache.put(sheetId, sheet);
            if (cache.size() > maxSize) {
                evictLeastRecentlyUsed();
            }
        }
        return sheet;
    }
}
```

#### 3.2.2 难点：内存占用

**问题描述**：
- 多sheet同时加载可能导致内存溢出
- 大数据量项目内存消耗严重

**解决方案**：

**方案1：弱引用缓存**
```java
public class Project {
    protected Map<String, WeakReference<SheetData>> sheetDataCache;
    
    public SheetData getSheetData(String sheetId) {
        WeakReference<SheetData> ref = sheetDataCache.get(sheetId);
        SheetData sheet = ref != null ? ref.get() : null;
        if (sheet == null) {
            sheet = loadSheetData(sheetId);
            sheetDataCache.put(sheetId, new WeakReference<>(sheet));
        }
        return sheet;
    }
}
```

**方案2：分页加载**
```java
public class SheetData {
    protected int pageSize = 1000;
    protected Map<Integer, List<Row>> pageCache;
    
    public Row getRow(int rowIndex) {
        int pageIndex = rowIndex / pageSize;
        if (!pageCache.containsKey(pageIndex)) {
            loadPage(pageIndex);
        }
        int pageIndexInPage = rowIndex % pageSize;
        return pageCache.get(pageIndex).get(pageIndexInPage);
    }
}
```

### 3.3 历史记录管理

#### 3.3.1 难点：跨sheet操作历史

**问题描述**：
- 跨sheet操作需要记录到多个sheet的历史
- 撤销/重做需要协调多个sheet

**解决方案**：

**方案1：复合HistoryEntry**
```java
public class CompositeHistoryEntry extends HistoryEntry {
    protected Map<String, HistoryEntry> sheetEntries;
    
    public void apply(Project project) {
        for (Map.Entry<String, HistoryEntry> entry : sheetEntries.entrySet()) {
            String sheetId = entry.getKey();
            HistoryEntry sheetEntry = entry.getValue();
            SheetData sheet = project.getSheetData(sheetId);
            sheetEntry.apply(sheet);
        }
    }
    
    public void revert(Project project) {
        // 反向顺序撤销
        List<String> sheetIds = new ArrayList<>(sheetEntries.keySet());
        Collections.reverse(sheetIds);
        for (String sheetId : sheetIds) {
            HistoryEntry sheetEntry = sheetEntries.get(sheetId);
            SheetData sheet = project.getSheetData(sheetId);
            sheetEntry.revert(sheet);
        }
    }
}
```

**方案2：操作链**
```java
public class OperationChain extends AbstractOperation {
    protected List<AbstractOperation> operations;
    protected List<String> sheetIds;
    
    public void apply(Project project) {
        for (int i = 0; i < operations.size(); i++) {
            AbstractOperation op = operations.get(i);
            String sheetId = sheetIds.get(i);
            SheetData sheet = project.getSheetData(sheetId);
            op.apply(sheet);
        }
    }
}
```

#### 3.3.2 难点：历史记录存储空间

**问题描述**：
- 多sheet项目的历史记录占用更多空间
- 长期使用可能导致磁盘空间不足

**解决方案**：

**方案1：定期清理**
```java
public class HistoryCleaner {
    protected int maxHistoryEntries = 100;
    protected long maxHistoryAge = 30 * 24 * 60 * 60 * 1000; // 30天
    
    public void clean(Project project) {
        for (String sheetId : project.getAllSheetIds()) {
            SheetHistory history = project.getSheetHistory(sheetId);
            cleanHistory(history);
        }
    }
    
    private void cleanHistory(SheetHistory history) {
        // 清理过多的历史条目
        while (history.getPastEntries().size() > maxHistoryEntries) {
            HistoryEntry oldest = history.getPastEntries().get(0);
            oldest.delete();
            history.removePastEntry(0);
        }
        
        // 清理过期的历史条目
        long now = System.currentTimeMillis();
        List<HistoryEntry> toRemove = new ArrayList<>();
        for (HistoryEntry entry : history.getPastEntries()) {
            if (now - entry.time.getTime() > maxHistoryAge) {
                toRemove.add(entry);
            }
        }
        for (HistoryEntry entry : toRemove) {
            entry.delete();
            history.removePastEntry(entry);
        }
    }
}
```

**方案2：增量压缩**
```java
public class HistoryCompressor {
    public void compress(SheetHistory history) {
        // 将多个连续的小操作合并为一个大操作
        List<HistoryEntry> entries = history.getPastEntries();
        List<HistoryEntry> compressed = new ArrayList<>();
        
        int i = 0;
        while (i < entries.size()) {
            HistoryEntry current = entries.get(i);
            if (canCompressWithNext(current, entries, i)) {
                HistoryEntry merged = mergeEntries(current, entries.get(i + 1));
                compressed.add(merged);
                i += 2;
            } else {
                compressed.add(current);
                i++;
            }
        }
        
        history.replacePastEntries(compressed);
    }
}
```

### 3.4 数据迁移

#### 3.4.1 难点：旧项目兼容

**问题描述**：
- 现有单sheet项目需要能够正常加载
- 需要支持从单sheet升级到多sheet

**解决方案**：

**方案1：自动检测和转换**
```java
public class ProjectLoader {
    static public Project load(File projectDir, long id) throws IOException {
        File projectFile = new File(projectDir, "project.txt");
        
        // 检测项目格式
        ProjectFormat format = detectProjectFormat(projectFile);
        
        if (format == ProjectFormat.SINGLE_SHEET) {
            // 转换为多sheet格式
            return convertToMultiSheet(projectFile, id);
        } else {
            // 直接加载多sheet格式
            return loadMultiSheetProject(projectFile, id);
        }
    }
    
    private static Project convertToMultiSheet(File projectFile, long id) throws IOException {
        // 加载旧格式
        Project project = loadOldFormat(projectFile, id);
        
        // 创建默认sheet
        SheetData defaultSheet = new SheetData("default", "Default Sheet", "");
        defaultSheet.rows = project.rows;
        defaultSheet.columnModel = project.columnModel;
        defaultSheet.recordModel = project.recordModel;
        
        // 转换为新格式
        project.isMultiSheetProject = true;
        project.activeSheetId = "default";
        project.addSheetData(defaultSheet);
        
        return project;
    }
}
```

**方案2：版本标记**
```java
public class Project {
    protected String formatVersion;
    
    public void saveToWriter(Writer writer, Properties options) throws IOException {
        writer.write(RefineServlet.VERSION);
        writer.write('\n');
        
        writer.write("formatVersion=");
        writer.write(formatVersion != null ? formatVersion : "1.0");
        writer.write('\n');
        
        writer.write("isMultiSheetProject=");
        writer.write(Boolean.toString(isMultiSheetProject));
        writer.write('\n');
        
        // ... 其他数据
    }
    
    static private Project loadFromReader(LineNumberReader reader, long id, Pool pool) throws IOException {
        String version = reader.readLine();
        
        Project project = new Project(id);
        
        String line;
        while ((line = reader.readLine()) != null) {
            int equal = line.indexOf('=');
            String field = line.substring(0, equal);
            String value = line.substring(equal + 1);
            
            if ("formatVersion".equals(field)) {
                project.formatVersion = value;
            } else if ("isMultiSheetProject".equals(field)) {
                project.isMultiSheetProject = Boolean.parseBoolean(value);
            }
            // ... 其他字段
        }
        
        // 如果没有formatVersion，说明是旧格式
        if (project.formatVersion == null) {
            project = convertLegacyProject(project);
        }
        
        return project;
    }
}
```

### 3.5 错误恢复

#### 3.5.1 难点：损坏数据恢复

**问题描述**：
- 保存过程中断电或崩溃可能导致数据损坏
- 需要能够恢复到一致状态

**解决方案**：

**方案1：原子写入**
```java
public class ProjectSaver {
    public void save(Project project, File projectDir) throws IOException {
        File tempFile = new File(projectDir, "project.tmp");
        File targetFile = new File(projectDir, "project.txt");
        
        try {
            // 写入临时文件
            try (FileOutputStream out = new FileOutputStream(tempFile)) {
                project.saveToOutputStream(out, new Pool());
            }
            
            // 原子性替换
            Files.move(tempFile.toPath(), targetFile.toPath(), 
                StandardCopyOption.ATOMIC_MOVE, 
                StandardCopyOption.REPLACE_EXISTING);
                
        } catch (IOException e) {
            // 清理临时文件
            if (tempFile.exists()) {
                tempFile.delete();
            }
            throw e;
        }
    }
}
```

**方案2：备份机制**
```java
public class ProjectBackupManager {
    protected int maxBackups = 5;
    
    public void createBackup(Project project) throws IOException {
        File projectDir = project.getProjectDir();
        File projectFile = new File(projectDir, "project.txt");
        
        // 创建备份
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss");
        String timestamp = sdf.format(new Date());
        File backupFile = new File(projectDir, "project.backup." + timestamp);
        
        Files.copy(projectFile.toPath(), backupFile.toPath());
        
        // 清理旧备份
        cleanOldBackups(projectDir);
    }
    
    private void cleanOldBackups(File projectDir) {
        File[] backups = projectDir.listFiles((dir, name) -> 
            name.startsWith("project.backup."));
        
        if (backups != null && backups.length > maxBackups) {
            Arrays.sort(backups, Comparator.comparingLong(File::lastModified));
            for (int i = 0; i < backups.length - maxBackups; i++) {
                backups[i].delete();
            }
        }
    }
    
    public Project restoreFromBackup(File projectDir) throws IOException {
        File[] backups = projectDir.listFiles((dir, name) -> 
            name.startsWith("project.backup."));
        
        if (backups != null && backups.length > 0) {
            // 选择最新的备份
            Arrays.sort(backups, Comparator.comparingLong(File::lastModified).reversed());
            File latestBackup = backups[0];
            
            // 恢复备份
            File projectFile = new File(projectDir, "project.txt");
            Files.copy(latestBackup.toPath(), projectFile.toPath(), 
                StandardCopyOption.REPLACE_EXISTING);
            
            return loadProject(projectDir);
        }
        
        throw new IOException("No backup found");
    }
}
```

**方案3：校验和恢复**
```java
public class ProjectValidator {
    public ValidationResult validate(Project project) {
        ValidationResult result = new ValidationResult();
        
        for (SheetData sheet : project.getAllSheetData()) {
            validateSheet(sheet, result);
        }
        
        return result;
    }
    
    private void validateSheet(SheetData sheet, ValidationResult result) {
        // 验证行数
        if (sheet.rows.size() != sheet.rowCount) {
            result.addError("Row count mismatch");
        }
        
        // 验证列模型
        for (Row row : sheet.rows) {
            if (row.cells.size() > sheet.columnModel.getMaxCellIndex() + 1) {
                result.addError("Cell index exceeds column model");
            }
        }
        
        // 验证历史记录
        SheetHistory history = sheet.getHistory();
        for (HistoryEntry entry : history.getPastEntries()) {
            if (entry.getChange() == null) {
                result.addWarning("History entry missing change data");
            }
        }
    }
    
    public void repair(Project project, ValidationResult validation) {
        for (String error : validation.getErrors()) {
            if (error.contains("Row count mismatch")) {
                repairRowCount(project);
            } else if (error.contains("Cell index exceeds")) {
                repairColumnModel(project);
            }
        }
    }
}
```

## 4. 实施建议

### 4.1 推荐方案

**采用方案C（混合存储）**，理由：
1. 平衡了性能和复杂度
2. 支持延迟加载和并行操作
3. 便于备份和迁移
4. 扩展性好

### 4.2 实施步骤

1. **第一阶段**：实现基础存储格式
   - 扩展Project类支持多sheet
   - 实现SheetData类
   - 修改序列化/反序列化逻辑

2. **第二阶段**：实现历史记录管理
   - 扩展HistoryEntry支持sheetId
   - 实现SheetHistory类
   - 修改撤销/重做逻辑

3. **第三阶段**：优化性能
   - 实现延迟加载
   - 添加缓存机制
   - 优化大文件处理

4. **第四阶段**：增强可靠性
   - 实现原子写入
   - 添加备份机制
   - 实现数据校验和恢复

### 4.3 测试策略

1. **单元测试**：测试各个存储组件
2. **集成测试**：测试完整的数据流
3. **性能测试**：测试大文件加载和保存
4. **并发测试**：测试多sheet并发操作
5. **恢复测试**：测试数据损坏恢复

## 5. 结论

多sheet数据持久化虽然复杂，但通过合理的设计和实施，可以实现稳定可靠的多sheet存储。关键点包括：

1. **选择合适的存储方案**：推荐混合存储方案
2. **保证数据一致性**：使用合适的锁机制和事务
3. **优化性能**：实现延迟加载和缓存
4. **增强可靠性**：实现原子写入和备份机制
5. **确保兼容性**：支持旧项目自动转换

通过分阶段实施和充分测试，可以确保多sheet存储功能的稳定性和可靠性。
