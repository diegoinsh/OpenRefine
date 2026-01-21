import com.google.refine.extension.records.db.QueryBuilder;
import com.google.refine.extension.records.db.model.SchemaProfile;
import com.google.refine.extension.records.db.model.SchemaProfile.FieldMapping;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Simple test to verify QueryBuilder generates correct SQL
 */
public class TestQueryBuilder {
    
    public static void main(String[] args) {
        System.out.println("Testing QueryBuilder SQL generation...\n");
        
        // Test 1: Basic query with file mapping
        testBasicQueryWithFileMapping();
        
        // Test 2: Query with no field mappings and file mapping
        testNoFieldMappingsWithFileMapping();
        
        System.out.println("\nAll tests completed!");
    }
    
    private static void testBasicQueryWithFileMapping() {
        System.out.println("Test 1: Basic query with file mapping");
        System.out.println("==========================================");
        
        SchemaProfile profile = new SchemaProfile();
        profile.setDialect("mysql");
        profile.setMainTable("test_table");
        profile.setRecordIdColumn("id");
        
        // Add field mappings
        List<FieldMapping> fieldMappings = new ArrayList<>();
        FieldMapping mapping1 = new FieldMapping();
        mapping1.setColumnName("name");
        mapping1.setColumnLabel("name");
        fieldMappings.add(mapping1);
        
        FieldMapping mapping2 = new FieldMapping();
        mapping2.setColumnName("age");
        mapping2.setColumnLabel("age");
        fieldMappings.add(mapping2);
        
        profile.setFieldMappings(fieldMappings);
        
        // Add file mapping
        Map<String, Object> fileMapping = new HashMap<>();
        fileMapping.put("source", "main");
        fileMapping.put("field", "current_path");
        fileMapping.put("rootPath", "");
        fileMapping.put("columnLabel", "file_path");
        profile.setFileMapping(fileMapping);
        
        String query = QueryBuilder.buildSelectQuery(profile);
        System.out.println("Generated SQL:");
        System.out.println(query);
        System.out.println();
        
        // Verify no syntax errors (basic check)
        if (query.contains(", , ") || query.contains(",,")) {
            System.out.println("ERROR: Query contains double commas!");
        } else if (query.indexOf("FROM") < query.indexOf("file_path")) {
            System.out.println("SUCCESS: file_path is in SELECT clause before FROM");
        } else {
            System.out.println("ERROR: file_path is not in correct position!");
        }
        System.out.println();
    }
    
    private static void testNoFieldMappingsWithFileMapping() {
        System.out.println("Test 2: Query with no field mappings and file mapping");
        System.out.println("========================================================");
        
        SchemaProfile profile = new SchemaProfile();
        profile.setDialect("mysql");
        profile.setMainTable("test_table");
        profile.setRecordIdColumn("id");
        
        // No field mappings - should use m.*
        
        // Add file mapping
        Map<String, Object> fileMapping = new HashMap<>();
        fileMapping.put("source", "main");
        fileMapping.put("field", "current_path");
        fileMapping.put("rootPath", "");
        fileMapping.put("columnLabel", "file_path");
        profile.setFileMapping(fileMapping);
        
        String query = QueryBuilder.buildSelectQuery(profile);
        System.out.println("Generated SQL:");
        System.out.println(query);
        System.out.println();
        
        // Verify no syntax errors (basic check)
        if (query.contains(", , ") || query.contains(",,")) {
            System.out.println("ERROR: Query contains double commas!");
        } else if (query.indexOf("FROM") < query.indexOf("file_path")) {
            System.out.println("SUCCESS: file_path is in SELECT clause before FROM");
        } else {
            System.out.println("ERROR: file_path is not in correct position!");
        }
        System.out.println();
    }
}

