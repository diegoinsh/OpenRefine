# Records-Assets Backend Design (File Preview Extension)

## Overview

Records-Assets is a new extension providing file tree navigation and preview capabilities for imported records.

## Security Model

### allowedRoots Configuration

**Default**: Empty array `[]` (NOT configured)

**Requirement**: Must be explicitly configured at deployment time based on installation location

**Configuration Format**:
```json
{
  "recordsAssets": {
    "allowedRoots": [
      "/path/to/archive1",
      "/path/to/archive2"
    ]
  }
}
```

**Behavior**:
- If `allowedRoots` is empty or not configured, return 403 ASSETS_ROOT_NOT_ALLOWED
- All file paths must be under one of the allowed roots
- Canonical path validation required

### Canonical Path Validation

- Resolve all symlinks and relative paths
- Prevent directory traversal attacks (../)
- Verify resolved path is under allowed root
- Return 403 if validation fails

## API Endpoints

### 1. List Directory Tree

**Endpoint**: `GET /command/records-assets/list`

**Parameters**:
- `root`: Base directory (must be in allowedRoots)
- `path`: Relative path within root (optional)
- `depth`: Max directory depth (default: 3, max: 10)
- `page`: Page number for pagination (default: 1)
- `pageSize`: Items per page (default: 50, max: 200)

**Response**:
```json
{
  "status": "ok",
  "root": "/path/to/archive",
  "path": "subdir",
  "items": [
    {
      "name": "file.jpg",
      "type": "file",
      "size": 1024000,
      "modified": "2025-11-10T10:00:00Z",
      "thumbnail": "/command/records-assets/preview?file=...&type=thumb"
    },
    {
      "name": "subfolder",
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

**Error Responses**:
- 403 ASSETS_ROOT_NOT_ALLOWED: allowedRoots not configured or path not allowed
- 404 NOT_FOUND: Directory not found
- 400 BAD_REQUEST: Invalid parameters

### 2. Preview File

**Endpoint**: `GET /command/records-assets/preview`

**Parameters**:
- `file`: Full file path (must be under allowedRoots)
- `type`: Preview type (thumb, raw, pdf, word)
- `width`: Thumbnail width (default: 200, max: 500)
- `height`: Thumbnail height (default: 200, max: 500)

**Supported File Types**:
- **Images** (jpg, png, gif, webp): Thumbnail + original
- **PDF**: Embedded viewer (pdf.js)
- **Word** (docx, doc): Download link
- **Text**: Plain text preview

**Response**:
- Images: Binary image data with Content-Type header
- PDF: HTML with embedded pdf.js viewer
- Word: Redirect to download or HTML preview
- Text: Plain text with Content-Type: text/plain

**Error Responses**:
- 403 ASSETS_ROOT_NOT_ALLOWED: Path not allowed
- 404 NOT_FOUND: File not found
- 415 UNSUPPORTED_MEDIA_TYPE: File type not supported

## Performance Optimization

### Thumbnail Caching

- Generate thumbnails on first request
- Cache in `{dataDir}/extensions/records-assets/cache/`
- Cache key: SHA256(file_path + width + height)
- TTL: 30 days (configurable)
- Cleanup: Remove stale cache on startup

### HTTP Range Support

- Support Range header for large files
- Enable resume capability
- Reduce bandwidth for partial downloads

### Lazy Loading

- Load directory tree on demand
- Limit depth to prevent deep recursion
- Paginate results for large directories
- Show loading indicator in UI

### ResultSet Streaming

- Stream large files without loading into memory
- Use chunked transfer encoding
- Support partial content (206 Partial Content)

## Integration with Records-DB

- Records-DB exports `file_root` column
- Frontend passes `file_root` to Assets endpoints
- No direct database coupling
- Communication via OpenRefine project data

## Configuration

**Default Configuration**:
```json
{
  "recordsAssets": {
    "allowedRoots": [],
    "thumbnailCache": {
      "enabled": true,
      "ttl": 2592000,
      "maxSize": 1073741824
    },
    "preview": {
      "maxDepth": 10,
      "maxItems": 200,
      "supportedTypes": ["jpg", "png", "gif", "pdf", "docx", "doc", "txt"]
    }
  }
}
```

## Error Handling

- Log all security violations
- Return generic error messages to client
- Provide detailed logs for debugging
- Monitor for suspicious access patterns

## Testing

- Unit tests for path validation
- Integration tests for endpoints
- Security tests for traversal attacks
- Performance tests for large directories

