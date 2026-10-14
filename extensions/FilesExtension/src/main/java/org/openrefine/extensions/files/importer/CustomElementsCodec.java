package org.openrefine.extensions.files.importer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

public class CustomElementsCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static String toJson(List<CustomElementType> types) {
        if (types == null || types.isEmpty()) return "[]";
        try {
            return MAPPER.writeValueAsString(types);
        } catch (Exception e) {
            return "[]";
        }
    }

    public static List<CustomElementType> fromJson(String json) {
        if (json == null || json.trim().isEmpty()) return new ArrayList<>();
        try {
            return MAPPER.readValue(json, new TypeReference<List<CustomElementType>>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }
}
