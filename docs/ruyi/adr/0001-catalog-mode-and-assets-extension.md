# ADR-0001: Catalog Mode + Assets Extension

## Status

Accepted

## Context

OpenRefine's database extension currently supports only SQL mode, which requires users to write SQL queries. This creates barriers for non-technical users and doesn't leverage the structured metadata available in document management systems.

We need to support:
1. Document + metadata import from various archival and non-archival systems
2. File preview capabilities (images, PDFs, Word documents)
3. Cross-system adaptability without code changes
4. Security isolation between data import and file access
5. Internationalization (i18n) for global deployment

## Decision

We will implement two complementary extensions:

1. **Records-DB**: Enhance the database extension with Catalog Mode as default
   - Schema Profile configuration system for flexible table mapping
   - Preset templates (Kubao, Flat Table, Generic JSON-in-field)
   - P0 (server-side JSON parsing) and P1 (SQL pushdown) filtering strategies
   - No-code wizard UI with i18n support

2. **Records-Assets**: New extension for file preview
   - Lazy-load directory tree navigation
   - Support for images, PDFs, Word documents
   - allowedRoots whitelist (default empty, must be configured at deployment)
   - Canonical path validation for security

## Considered Alternatives

### Alternative 1: Single Extension with SQL Only
- **Pros**: Simpler implementation
- **Cons**: High barrier for non-technical users, no file preview, limited cross-system support

### Alternative 2: Single Extension with Everything
- **Pros**: Unified codebase
- **Cons**: Tight coupling, security risks, harder to maintain

### Alternative 3: Catalog Mode Only (No SQL)
- **Pros**: Simpler UI
- **Cons**: Loses flexibility for advanced users

## Decision Rationale

The two-extension approach provides:

1. **Flexibility**: Catalog Mode for typical users, SQL mode for advanced users
2. **Security**: Separate extensions allow independent security policies
3. **Maintainability**: Clear separation of concerns
4. **Extensibility**: Easy to add new presets or file types
5. **Cross-system Support**: Schema Profile enables adaptation to different systems
6. **i18n Ready**: All UI text uses i18n keys from the start

## Constraints

1. **allowedRoots Default**: Must be empty array [] by default
   - Rationale: System can be installed on Windows, Mac, or Linux in any location
   - User must explicitly configure based on their installation

2. **i18n Mandatory**: All UI text must use OpenRefine i18n keys
   - Rationale: Global deployment requires multi-language support
   - No hardcoded strings allowed

3. **Database Support Priority**:
   - MySQL: Full support (JSON_EXTRACT)
   - PostgreSQL: Full support (json->>)
   - MariaDB: Full support (JSON_EXTRACT)
   - SQLite: P0 only (server-side parsing)

4. **Generic Naming**: No specific references in code/UI
   - Rationale: Solution must be adaptable to other systems
   - Kubao is just one preset template

## Consequences

### Positive

1. **Simplified User Experience**: Catalog Mode reduces learning curve
2. **Enhanced Security**: Separate extensions allow independent security policies
3. **Better Performance**: SQL pushdown enables efficient filtering
4. **Cross-system Adaptability**: Schema Profile supports different table structures
5. **Maintainability**: Clear separation of concerns
6. **Global Deployment**: i18n support from the start
7. **Extensibility**: Easy to add new presets or file types

### Negative

1. **Increased Complexity**: Two extensions instead of one
2. **More Testing**: Need to test interactions between extensions
3. **Configuration Burden**: Users must configure allowedRoots
4. **Learning Curve**: Developers need to understand both extensions

### Risks and Mitigation

1. **Risk**: Users forget to configure allowedRoots
   - **Mitigation**: Clear error message (403) when not configured, documentation

2. **Risk**: Security vulnerabilities in path validation
   - **Mitigation**: Canonical path validation, security testing, code review

3. **Risk**: Performance issues with large datasets
   - **Mitigation**: Streaming, pagination, caching, SQL pushdown

4. **Risk**: i18n keys not properly maintained
   - **Mitigation**: Automated i18n key validation, documentation

## Implementation Notes

1. **Phase 1**: Records-DB with Catalog Mode (P0 strategy)
2. **Phase 2**: Records-Assets with basic file preview
3. **Phase 3**: SQL pushdown optimization (P1 strategy)
4. **Phase 4**: Additional presets and file types

## Related Decisions

- ADR-0002: Schema Profile Configuration Format (TBD)
- ADR-0003: i18n Key Naming Convention (TBD)

## References

- docs/ruyi/design/architecture.md
- docs/ruyi/dev/be_records_db.md
- docs/ruyi/dev/ui_records_db.md
- docs/ruyi/dev/be_records_assets.md
- docs/ruyi/api/endpoints.md

