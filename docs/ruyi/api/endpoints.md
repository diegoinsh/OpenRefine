# API Endpoints (Catalog Mode + Assets)

## Records-DB Endpoints (Catalog Mode)

### Initialize UI

**Endpoint**: `POST /command/database-import-controller`

**Parameters**:
- `subCommand`: `initialize-ui`
- `mode`: `catalog` (default) or `sql`

**Request Body**:
```json
{
  "databaseType": "mysql",
  "host": "localhost",
  "port": 3306,
  "database": "ruyi",
  "username": "user",
  "password": "pass"
}
```

**Response**:
```json
{
  "status": "ok",
  "mode": "catalog",
  "presets": ["kubao", "flat_table", "generic_json"],
  "supportedDialects": ["mysql", "postgres", "mariadb", "sqlite"]
}
```

### Parse Preview

**Endpoint**: `POST /command/database-import-controller`

**Parameters**:
- `subCommand`: `parse-preview`
- `mode`: `catalog` or `sql`

**Request Body**:
```json
{
  "profile": {
    "mode": "catalog",
    "preset": "kubao",
    "selectedFields": ["code", "name"],
    "filters": {
      "excludeExported": true,
      "conditions": []
    }
  }
}
```

**Response**:
```json
{
  "status": "ok",
  "rows": [
    {
      "code": "001",
      "name": "Document 1",
      "file_root": "/archive/001"
    }
  ],
  "rowCount": 100,
  "totalCount": 5000,
  "columns": ["code", "name", "file_root"]
}
```

### Create Project

**Endpoint**: `POST /command/database-import-controller`

**Parameters**:
- `subCommand`: `create-project`
- `mode`: `catalog` or `sql`

**Request Body**:
```json
{
  "projectName": "My Import",
  "profile": {
    "mode": "catalog",
    "preset": "kubao",
    "selectedFields": ["code", "name"],
    "filters": {
      "excludeExported": true
    }
  }
}
```

**Response**:
```json
{
  "status": "ok",
  "projectId": 12345,
  "projectName": "My Import",
  "rowCount": 5000
}
```

## Records-Assets Endpoints (File Preview)

### List Directory

**Endpoint**: `GET /command/records-assets/list`

**Query Parameters**:
- `root`: Base directory path (required, must be in allowedRoots)
- `path`: Relative path within root (optional)
- `depth`: Max directory depth (default: 3, max: 10)
- `page`: Page number (default: 1)
- `pageSize`: Items per page (default: 50, max: 200)

**Response**:
```json
{
  "status": "ok",
  "root": "/archive",
  "path": "subdir",
  "items": [
    {
      "name": "file.jpg",
      "type": "file",
      "size": 1024000,
      "modified": "2025-11-10T10:00:00Z",
      "thumbnail": "/command/records-assets/preview?file=/archive/subdir/file.jpg&type=thumb"
    },
    {
      "name": "folder",
      "type": "directory",
      "itemCount": 5
    }
  ],
  "pagination": {
    "page": 1,
    "pageSize": 50,
    "total": 120
  }
}
```

**Error Response** (403):
```json
{
  "status": "error",
  "code": "ASSETS_ROOT_NOT_ALLOWED",
  "message": "allowedRoots not configured or path not allowed"
}
```

### Preview File

**Endpoint**: `GET /command/records-assets/preview`

**Query Parameters**:
- `file`: Full file path (required, must be under allowedRoots)
- `type`: Preview type - `thumb`, `raw`, `pdf`, `word` (default: raw)
- `width`: Thumbnail width in pixels (default: 200, max: 500)
- `height`: Thumbnail height in pixels (default: 200, max: 500)

**Response**:
- **Images**: Binary image data
  - Content-Type: image/jpeg, image/png, etc.
  - Supports HTTP Range requests
  
- **PDF**: HTML with embedded pdf.js viewer
  - Content-Type: text/html
  
- **Word**: Download or HTML preview
  - Content-Type: application/vnd.openxmlformats-officedocument.wordprocessingml.document
  
- **Text**: Plain text
  - Content-Type: text/plain

**Error Response** (403):
```json
{
  "status": "error",
  "code": "ASSETS_ROOT_NOT_ALLOWED",
  "message": "File path not allowed"
}
```

**Error Response** (404):
```json
{
  "status": "error",
  "code": "FILE_NOT_FOUND",
  "message": "File not found"
}
```

## Error Codes

### Common Errors

- `INVALID_REQUEST`: Missing or invalid parameters
- `AUTHENTICATION_FAILED`: Database connection failed
- `PERMISSION_DENIED`: User lacks required permissions
- `INTERNAL_ERROR`: Server error

### Records-DB Specific

- `INVALID_PROFILE`: Profile configuration invalid
- `NO_FIELDS_SELECTED`: No fields selected for import
- `MAPPING_FAILED`: Field mapping failed
- `QUERY_FAILED`: Database query failed

### Records-Assets Specific

- `ASSETS_ROOT_NOT_ALLOWED`: allowedRoots not configured or path not allowed
- `FILE_NOT_FOUND`: File or directory not found
- `UNSUPPORTED_MEDIA_TYPE`: File type not supported for preview
- `TRAVERSAL_ATTACK`: Attempted directory traversal detected

## Response Format

All responses follow this format:

```json
{
  "status": "ok|error",
  "code": "ERROR_CODE",
  "message": "Human readable message",
  "data": {}
}
```

## HTTP Status Codes

- 200 OK: Successful request
- 400 Bad Request: Invalid parameters
- 403 Forbidden: Access denied (allowedRoots not configured)
- 404 Not Found: Resource not found
- 500 Internal Server Error: Server error

