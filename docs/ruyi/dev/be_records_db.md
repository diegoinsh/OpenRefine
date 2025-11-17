# Records-DB Backend Design (Catalog Mode)

## Overview

Records-DB is an enhancement to the database extension, providing Catalog Mode as the default import method with optional SQL mode for advanced users.

## Controller Enhancement

**Endpoint**: `/command/database-import-controller`

**Subcommands**:
- `initialize-ui`: Load UI with mode parameter (catalog/sql)
- `parse-preview`: Generate preview data
- `create-project`: Create OpenRefine project

**Mode Parameter**:
- `mode=catalog` (default): Catalog Mode with Schema Profile
- `mode=sql` (advanced): Native SQL query mode

## Schema Profile

Configuration-driven mapping of database tables to import structure:

```json
{
  "mode": "catalog",
  "dialect": "mysql|postgres",
  "preset": "kubao|flat_table|generic_json",
  "tables": {
    "project": "project",
    "bind_table": "project_bind_table",
    "field_dict": "project_bind_field",
    "data": "project_bind_data",
    "file": "file_book"
  },
  "columns": {
    "data.field_data": "json",
    "file.current_path": "file_root",
    "file.scan_path": "file_root_raw",
    "file.exported": "export_flag"
  },
  "flag_values": {
    "exported_done": 1,
    "exported_undo": 0,
    "exported_clean": -1
  }
}
```

## Query Strategies

### P0: Server-side JSON Parsing (Universal)

- Parse field_data JSON on server
- Apply filters in application code
- Works with all database dialects
- Simpler implementation, slightly slower

### P1: Dialect SQL Pushdown (Performance)

**MySQL**:
```sql
SELECT JSON_EXTRACT(field_data, '$.code') AS field_value
FROM project_bind_data
WHERE JSON_EXTRACT(field_data, '$.code') = 'value'
```

**PostgreSQL**:
```sql
SELECT field_data::json->>'code' AS field_value
FROM project_bind_data
WHERE field_data::json->>'code' = 'value'
```

## Column Mapping

**Base Columns**:
- `book_id`: Entry ID
- `record_type`: Entry type (case/file/piece)
- `data_key`: Entry number/code
- `project_id`: Project ID
- `bind_table_id`: Table ID

**Dictionary Fields**:
- Mapped from project_bind_field.code → project_bind_field.name
- Used as column headers in OpenRefine

## File Path Mapping (Simplified)

**Configuration Structure** (`fileMapping`):

```json
{
  "fileMapping": {
    "rootPath": "/home/kubao/scanFiles",
    "source": "main",
    "field": "current_path",
    "columnLabel": "file_path"
  }
}
```

**Configuration Fields**:

1. **`rootPath`** (optional, string):
   - Fixed root directory prefix on the file system
   - Can be empty - if empty, the field value is treated as absolute path
   - Example: `/home/kubao/scanFiles` or `D:\scanFiles`

2. **`source`** (required, string):
   - Where the path field comes from
   - Values: `"main"` | `"exportedJoin"` | `"join"`
   - `"main"`: Field from main table
   - `"exportedJoin"`: Field from exclude-exported join table (if configured in filters)
   - `"join"`: Field from custom join table (configured in filters.conditions)

3. **`field`** (required, string):
   - Column name in the source table
   - Example: `"current_path"`, `"scan_path"`

4. **`joinTable`**, **`mainKey`**, **`joinKey`** (required when `source="join"`):
   - Identifies which custom join to use
   - Must match a join already configured in `filters.conditions`
   - Example:
     ```json
     {
       "source": "join",
       "joinTable": "file_book",
       "mainKey": "book_id",
       "joinKey": "id",
       "field": "current_path"
     }
     ```

5. **`columnLabel`** (optional, string):
   - Custom column name in OpenRefine export
   - Default: `"file_path"`

**SQL Generation**:

- If `rootPath` is configured:
  - PostgreSQL: `rootPath || '/' || table_alias.field AS columnLabel`
  - MySQL: `CONCAT(rootPath, '/', table_alias.field) AS columnLabel`
- If `rootPath` is empty:
  - `table_alias.field AS columnLabel`
- The file path column is always appended as the **last column** in SELECT

**Examples**:

1. **Main table field with root path**:
```json
{
  "mainTable": "file_book",
  "fileMapping": {
    "rootPath": "/home/kubao/scanFiles",
    "source": "main",
    "field": "current_path",
    "columnLabel": "file_path"
  }
}
```
SQL: `'/home/kubao/scanFiles' || '/' || m.current_path AS file_path`

2. **Exported join table field**:
```json
{
  "filters": {
    "exportedJoin": {
      "joinTable": "file_book",
      "mainColumn": "book_id",
      "joinColumn": "id"
    }
  },
  "fileMapping": {
    "rootPath": "",
    "source": "exportedJoin",
    "field": "scan_path",
    "columnLabel": "original_path"
  }
}
```
SQL: `je.scan_path AS original_path`

3. **Custom join table field**:
```json
{
  "filters": {
    "conditions": [
      {
        "source": "join",
        "joinTable": "archive_files",
        "mainKey": "file_id",
        "joinKey": "id",
        "field": "status",
        "operator": "=",
        "value": "active"
      }
    ]
  },
  "fileMapping": {
    "rootPath": "/mnt/archive",
    "source": "join",
    "joinTable": "archive_files",
    "mainKey": "file_id",
    "joinKey": "id",
    "field": "archive_path",
    "columnLabel": "archived_file"
  }
}
```
SQL: `'/mnt/archive' || '/' || j1.archive_path AS archived_file`

**Backward Compatibility**:

Old fields (`fileRootColumn`, `fileRootRawColumn`, `allowedRoots`) are deprecated but still supported:
- If `fileMapping` exists, use new structure
- If `fileMapping` is missing but old fields exist, convert internally:
  - `fileRootColumn` → `fileMapping.source="main"`, `fileMapping.field=fileRootColumn`
  - `allowedRoots[0]` → `fileMapping.rootPath` (use first root only)

## Export Flag Filtering

**When Configured**:
- Show "Exclude exported" checkbox in UI
- Filter: `file_book.exported = 0` (not exported)

**When Not Configured**:
- Hide "Exclude exported" checkbox
- No export flag filtering applied

## Preset Templates

### Kubao
- Auto-detect tables: project, project_bind_table, project_bind_field, project_bind_data, file_book
- Support export flag filtering
- Field dictionary mapping

### Flat Table
- Single table selection
- No export flag
- Optional file root column or global root

### Generic JSON-in-field
- Custom JSON field mapping
- Flexible field path configuration

## Database Support Priority

1. **MySQL**: Full support (JSON_EXTRACT)
2. **PostgreSQL**: Full support (json->>)
3. **MariaDB**: Full support (JSON_EXTRACT)
4. **SQLite**: P0 only (server-side parsing)

## Integration with Assets Extension

- Records-DB exports file path column (configured via `fileMapping`)
- Assets extension uses the file path column to load directory tree
- No direct coupling between extensions
- Communication via OpenRefine project columns
- `rootPath` in fileMapping can be used for security validation (similar to old `allowedRoots`)

## Performance Considerations

- Stream ResultSet for large datasets
- Limit preview to 100 rows
- Cache field dictionary lookups
- Use connection pooling
- Support pagination for preview

