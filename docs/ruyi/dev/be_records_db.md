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
- `file_root`: Current path (from file_book.current_path)
- `file_root_raw`: Scan path (from file_book.scan_path)
- `exported`: Export flag (optional, only if configured)

**Dictionary Fields**:
- Mapped from project_bind_field.code → project_bind_field.name
- Used as column headers in OpenRefine

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

- Records-DB exports `file_root` column
- Assets extension uses `file_root` to load directory tree
- No direct coupling between extensions
- Communication via OpenRefine project columns

## Performance Considerations

- Stream ResultSet for large datasets
- Limit preview to 100 rows
- Cache field dictionary lookups
- Use connection pooling
- Support pagination for preview

