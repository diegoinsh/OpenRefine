# Records-DB Frontend Design (Catalog Mode UI)

## i18n Requirements

**All UI text MUST use OpenRefine i18n keys, NO hardcoded strings**

Key naming convention:
- `records.db.catalog.*`: Catalog Mode UI
- `records.db.filters.*`: Filter UI
- `records.db.preview.*`: Preview UI
- `records.db.wizard.*`: Wizard steps

## Wizard Flow

### Step 1: Select Profile

**i18n Keys**:
- `records.db.wizard.selectProfile.title`
- `records.db.wizard.selectProfile.preset`
- `records.db.wizard.selectProfile.custom`

**Options**:
- Kubao (preset)
- Flat Table (preset)
- Generic JSON-in-field (preset)
- Custom (manual configuration)

### Step 2: Select Fields

**i18n Keys**:
- `records.db.wizard.selectFields.title`
- `records.db.wizard.selectFields.available`
- `records.db.wizard.selectFields.selected`

**Behavior**:
- Display field dictionary (project_bind_field.name)
- Allow multi-select
- Show field type and required status
- Drag-to-reorder selected fields

### Step 3: Configure Filters

**i18n Keys**:
- `records.db.wizard.filters.title`
- `records.db.filters.excludeExported`
- `records.db.filters.fieldConditions`

**Conditional Display**:
- Show "Exclude exported" checkbox ONLY if export flag field is configured
- Show field condition builder for selected fields

**Field Condition Builder**:
- Operator: equals, contains, starts with, ends with, regex
- Value: text input or dropdown (if enum)
- Multiple conditions: AND/OR logic

### Step 4: Preview

**i18n Keys**:
- `records.db.wizard.preview.title`
- `records.db.wizard.preview.rowCount`
- `records.db.wizard.preview.testMapping`

**Behavior**:
- Display first 100 rows
- Show all selected columns
- Display actual data with field dictionary mapping
- "Test Mapping" button to validate configuration
- Show error messages if mapping fails

### Step 5: Create Project

**i18n Keys**:
- `records.db.wizard.create.title`
- `records.db.wizard.create.projectName`
- `records.db.wizard.create.button`

**Behavior**:
- Input project name
- Create OpenRefine project with imported data
- Show progress indicator
- Redirect to project on success

## Profile Management

**Save Profile**:
- Allow users to save current configuration as reusable profile
- Store as JSON file or in browser localStorage
- i18n key: `records.db.profile.save`

**Import Profile**:
- Load previously saved profile
- Auto-populate all wizard steps
- i18n key: `records.db.profile.import`

**Profile Format**:
```json
{
  "name": "My Kubao Project",
  "mode": "catalog",
  "preset": "specific",
  "selectedFields": ["code", "name", "date"],
  "filters": {
    "excludeExported": true,
    "conditions": []
  }
}
```

## Flat Table Mode (Simplified)

**Single-page UI** for non-archival systems:

1. Select table
2. Select fields
3. Specify file root (column or global path)
4. Preview
5. Create project

**i18n Keys**:
- `records.db.flatTable.selectTable`
- `records.db.flatTable.fileRootColumn`
- `records.db.flatTable.globalRoot`

## Error Handling

**i18n Keys**:
- `records.db.error.connectionFailed`
- `records.db.error.invalidProfile`
- `records.db.error.noFieldsSelected`
- `records.db.error.mappingFailed`

**Display**:
- Show error message in modal or inline
- Provide "Back" button to retry
- Log detailed error to console

## Responsive Design

- Mobile-friendly wizard layout
- Collapsible sections for long field lists
- Scrollable preview table
- Touch-friendly buttons and inputs

## Accessibility

- Keyboard navigation support
- ARIA labels for all form elements
- Color-blind friendly UI
- Screen reader compatible

## Performance

- Lazy-load field dictionary
- Debounce filter condition changes
- Cache preview results
- Limit preview to 100 rows

