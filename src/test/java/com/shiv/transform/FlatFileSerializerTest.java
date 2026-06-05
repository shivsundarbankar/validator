package com.shiv.transform;

import com.shiv.transform.model.FlatFileConfig;
import com.shiv.transform.model.RecordTypeConfig;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.*;

class FlatFileSerializerTest {

    private final FlatFileSerializer serializer = new FlatFileSerializer();

    @Test
    void headerRecord_usesDefaultPipeDelimiter() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("orderNumber", "PO123");
        json.put("orderDate", "20260604");

        FlatFileConfig config = new FlatFileConfig(null, List.of(
                new RecordTypeConfig("ORDER", null, List.of("orderNumber", "orderDate"))
        ));

        String result = serializer.serialize(json, config);

        assertThat(result).isEqualTo("ORDER|PO123|20260604");
    }

    @Test
    void arrayRecord_producesOneLinePerItem() {
        List<Map<String, Object>> lines = List.of(
                Map.of("quantity", "10", "unitPrice", "19.99"),
                Map.of("quantity", "5",  "unitPrice", "49.99")
        );
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("orderNumber", "PO123");
        json.put("lineItems", lines);

        FlatFileConfig config = new FlatFileConfig(",", List.of(
                new RecordTypeConfig("ORDER", null,        List.of("orderNumber")),
                new RecordTypeConfig("LINE",  "lineItems", List.of("quantity", "unitPrice"))
        ));

        String result = serializer.serialize(json, config);

        assertThat(result).isEqualTo(
                "ORDER,PO123\nLINE,10,19.99\nLINE,5,49.99"
        );
    }

    @Test
    void missingField_producesEmptyColumnValue() {
        Map<String, Object> json = Map.of("orderNumber", "PO123");

        FlatFileConfig config = new FlatFileConfig("|", List.of(
                new RecordTypeConfig("ORDER", null, List.of("orderNumber", "missingField"))
        ));

        String result = serializer.serialize(json, config);

        assertThat(result).isEqualTo("ORDER|PO123|");
    }

    @Test
    void nestedFieldPath_resolvedCorrectly() {
        Map<String, Object> shipTo = new LinkedHashMap<>();
        shipTo.put("name",   "Acme Corp");
        shipTo.put("street", "123 Main St");

        Map<String, Object> json = new LinkedHashMap<>();
        json.put("orderNumber", "PO456");
        json.put("shipTo", shipTo);

        FlatFileConfig config = new FlatFileConfig("|", List.of(
                new RecordTypeConfig("HDR", null, List.of("orderNumber", "shipTo.name", "shipTo.street"))
        ));

        String result = serializer.serialize(json, config);

        assertThat(result).isEqualTo("HDR|PO456|Acme Corp|123 Main St");
    }

    @Test
    void emptyArray_producesNoLineRecords() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("orderNumber", "PO789");
        json.put("lineItems", List.of());

        FlatFileConfig config = new FlatFileConfig("|", List.of(
                new RecordTypeConfig("ORDER", null,        List.of("orderNumber")),
                new RecordTypeConfig("LINE",  "lineItems", List.of("quantity"))
        ));

        String result = serializer.serialize(json, config);

        assertThat(result).isEqualTo("ORDER|PO789");
    }

    @Test
    void customDelimiter_isRespectedAcrossAllRecords() {
        List<Map<String, Object>> items = List.of(Map.of("sku", "A001", "qty", "5"));
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("poNum", "PO1");
        json.put("items", items);

        FlatFileConfig config = new FlatFileConfig("\t", List.of(
                new RecordTypeConfig("H", null,    List.of("poNum")),
                new RecordTypeConfig("D", "items", List.of("sku", "qty"))
        ));

        String result = serializer.serialize(json, config);

        assertThat(result).isEqualTo("H\tPO1\nD\tA001\t5");
    }
}
