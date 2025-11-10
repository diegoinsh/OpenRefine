# Catalog Mode + Assets Architecture

## Core Design Principles

- **Default Catalog Mode**: Document + metadata processing; SQL mode as fallback
- **Generic Naming**: No "kubao" references; use Records-DB, Records-Assets
- **Schema Profile**: Configuration-driven, preset templates + custom mapping
- **i18n First**: All UI text uses OpenRefine i18n keys, no hardcoded strings
- **Security First**: allowedRoots whitelist (default []), canonical path validation

## Architecture Components

### 1. Records-DB (database extension enhancement)

**Responsibilities**:
- Connect to databases (MySQL/PostgreSQL/MariaDB/SQLite)
- Map table structures via Schema Profile
- Support P0 (server-side JSON parsing) and P1 (dialect SQL pushdown)
- Generate column header mapping (field dict name → header)
- Export data to OpenRefine project

**Modes**:
- Catalog Mode (default): Document + metadata, no-code filtering
- SQL Mode (advanced): Native SQL queries

### 2. Records-Assets (new extension)

**Responsibilities**:
- Lazy-load directory tree and preview
- Support images (thumbnail + original), PDF (embedded), Word (download)
- Enforce allowedRoots whitelist and canonical path validation
- Caching and performance optimization

## Data Flow

```
User selects Profile (preset/custom)
    ↓
Select fields (display dict name)
    ↓
Configure filters (exclude exported + field conditions)
    ↓
DB query (P0 server-side / P1 dialect pushdown)
    ↓
Column header mapping (dict name → header)
    ↓
Preview (limit=100)
    ↓
Create OpenRefine project
    ↓
Row selected → file_root (current priority, scan fallback)
    ↓
Assets extension: directory tree / preview (image/PDF/Word)
```

## Preset Templates

### Kubao
- Auto-recognize project / project_bind_table / project_bind_field / project_bind_data / file_book
- Support export flag filtering
- Field dictionary mapping

### Flat Table
- Single table mode (non-archival systems)
- Select main table, fields, file root column or global root
- No export flag

### Generic JSON-in-field
- Support JSON fields like field_data
- Custom JSON path and field mapping

## Security & Performance

### Security
- **allowedRoots**: Default [] (not configured), must be set at deployment
- **Canonical validation**: Normalize paths, prevent traversal attacks
- **Read-only**: Database connections read-only
- **CSRF protection**: Follow OpenRefine standards

### Performance
- **Lazy-load directory tree**: Pagination and depth limits
- **Thumbnail caching**: Avoid regeneration
- **ResultSet streaming**: Large dataset handling
- **Range support**: HTTP Range request optimization
- **Preview limits**: Max items in list, max depth in preview

## i18n Key Convention

- `records.db.catalog.title`: Catalog Mode title
- `records.db.filters.excludeExported`: Exclude exported
- `records.assets.list.title`: File tree title
- `records.assets.preview.notAllowed`: Preview not allowed (403)

## Alignment with Kubao

- Entry → file root: file_book.current_path (priority) / scan_path (fallback)
- Filter exported: file_book.exported=0 (marked by FileBookService.updateExported)
- Header mapping: project_bind_field.name
- Field data: project_bind_data.field_data (JSON, code→value)

## Next Steps

- Backend: DatabaseImportController enhancement, Assets endpoints, SQL pushdown
- Frontend: Wizard UI, no-code filter builder, file tree and preview
- Testing: Unit tests, integration tests, cross-system adaptation tests

