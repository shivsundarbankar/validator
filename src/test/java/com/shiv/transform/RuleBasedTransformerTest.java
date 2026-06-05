package com.shiv.transform;

import com.shiv.dtos.parse.*;
import com.shiv.transform.model.*;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.*;

class RuleBasedTransformerTest {

    private final RuleBasedTransformer transformer = new RuleBasedTransformer();

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private static EdiSegment seg(String tag, int docPos, String... values) {
        List<EdiElement> elements = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            elements.add(EdiElement.builder()
                    .position(i + 1).value(values[i]).components(List.of()).build());
        }
        return EdiSegment.builder().tag(tag).position(docPos).elements(elements).build();
    }

    private static EdiLoop loop(String loopId, String triggerTag, List<EdiNode> children) {
        Map<String, List<EdiSegment>> segIdx = new HashMap<>();
        for (EdiNode child : children) {
            if (child instanceof EdiSegment s)
                segIdx.computeIfAbsent(s.tag(), k -> new ArrayList<>()).add(s);
        }
        return EdiLoop.builder()
                .loopId(loopId).occurrence(1).triggerSegment(triggerTag)
                .segmentIndex(segIdx).children(children).build();
    }

    private static EdiTransaction tx(List<EdiNode> children) {
        Map<String, List<EdiSegment>> segIdx = new HashMap<>();
        for (EdiNode child : children) {
            if (child instanceof EdiSegment s)
                segIdx.computeIfAbsent(s.tag(), k -> new ArrayList<>()).add(s);
        }
        return EdiTransaction.builder()
                .transactionCode("856").controlNumber("0001")
                .segmentIndex(segIdx).children(children).build();
    }

    // ── SEGMENT_FIELD ─────────────────────────────────────────────────────────

    @Test
    void segmentField_extractsValueFromTopLevelSegment() {
        EdiTransaction tx = tx(List.of(seg("BEG", 1, "00", "NE", "PO12345")));

        var rule = new MappingRule(RuleType.SEGMENT_FIELD, "BEG", 3, "orderNumber",
                null, null, null, null, null, null, null);

        assertThat(transformer.transform(tx, List.of(rule))).containsEntry("orderNumber", "PO12345");
    }

    @Test
    void segmentField_dotNotation_createsNestedObject() {
        EdiTransaction tx = tx(List.of(seg("BEG", 1, "00", "NE", "PO12345", "", "20260604")));

        var rule = new MappingRule(RuleType.SEGMENT_FIELD, "BEG", 5, "header.orderDate",
                null, null, null, null, null, null, null);

        Map<String, Object> out = transformer.transform(tx, List.of(rule));

        assertThat((Map<String, Object>) out.get("header")).containsEntry("orderDate", "20260604");
    }

    @Test
    void segmentField_missingSegment_isSkippedSilently() {
        EdiTransaction tx = tx(List.of(seg("BEG", 1, "00", "NE", "PO12345")));

        var rule = new MappingRule(RuleType.SEGMENT_FIELD, "CUR", 2, "currency",
                null, null, null, null, null, null, null);

        assertThat(transformer.transform(tx, List.of(rule))).doesNotContainKey("currency");
    }

    @Test
    void segmentField_blankValue_isSkippedSilently() {
        EdiTransaction tx = tx(List.of(seg("BEG", 1, "00", "NE", "PO12345", "")));

        var rule = new MappingRule(RuleType.SEGMENT_FIELD, "BEG", 4, "purposeCode",
                null, null, null, null, null, null, null);

        assertThat(transformer.transform(tx, List.of(rule))).doesNotContainKey("purposeCode");
    }

    // ── SEGMENT_FIELD_QUALIFIED ───────────────────────────────────────────────

    @Test
    void segmentFieldQualified_picksCorrectOccurrenceByQualifier() {
        EdiTransaction tx = tx(List.of(
                seg("DTM", 2, "001", "20260410"),
                seg("DTM", 3, "002", "20260403")
        ));

        var cancelRule   = new MappingRule(RuleType.SEGMENT_FIELD_QUALIFIED,
                "DTM", 2, "cancelDate", null, null, 1, "001", null, null, null);
        var deliveryRule = new MappingRule(RuleType.SEGMENT_FIELD_QUALIFIED,
                "DTM", 2, "deliveryDate", null, null, 1, "002", null, null, null);

        Map<String, Object> out = transformer.transform(tx, List.of(cancelRule, deliveryRule));

        assertThat(out).containsEntry("cancelDate", "20260410")
                       .containsEntry("deliveryDate", "20260403");
    }

    @Test
    void segmentFieldQualified_noMatchingQualifier_isSkippedSilently() {
        EdiTransaction tx = tx(List.of(seg("DTM", 1, "001", "20260410")));

        var rule = new MappingRule(RuleType.SEGMENT_FIELD_QUALIFIED,
                "DTM", 2, "shipDate", null, null, 1, "011", null, null, null);

        assertThat(transformer.transform(tx, List.of(rule))).doesNotContainKey("shipDate");
    }

    @Test
    void segmentFieldQualified_defaultsFilterPositionToOne() {
        EdiTransaction tx = tx(List.of(
                seg("REF", 1, "IA", "7931"),
                seg("REF", 2, "CO", "ORDER-001")
        ));

        var rule = new MappingRule(RuleType.SEGMENT_FIELD_QUALIFIED,
                "REF", 2, "orderRef", null, null, null, "CO", null, null, null);

        assertThat(transformer.transform(tx, List.of(rule))).containsEntry("orderRef", "ORDER-001");
    }

    // ── LOOP_FIELD ────────────────────────────────────────────────────────────

    @Test
    void loopField_noFilter_extractsFromFirstOccurrence() {
        EdiLoop n1 = loop("1000", "N1", List.of(seg("N1", 10, "ST", "Ship To Corp")));

        var rule = new MappingRule(RuleType.LOOP_FIELD, "N1", 2, "partyName",
                "N1", null, null, null, null, null, null);

        assertThat(transformer.transform(tx(List.of(n1)), List.of(rule))).containsEntry("partyName", "Ship To Corp");
    }

    @Test
    void loopField_withFilter_picksCorrectOccurrence() {
        EdiLoop stLoop = loop("1000", "N1", List.of(
                seg("N1", 10, "ST", "Ship To Corp"),
                seg("N3", 11, "123 Ship St"),
                seg("N4", 12, "Chicago", "IL", "60601")
        ));
        EdiLoop btLoop = loop("1000", "N1", List.of(
                seg("N1", 20, "BT", "Bill To Corp"),
                seg("N3", 21, "456 Bill Ave"),
                seg("N4", 22, "New York", "NY", "10001")
        ));
        EdiTransaction tx = tx(List.of(stLoop, btLoop));

        var rules = List.of(
                new MappingRule(RuleType.LOOP_FIELD, "N1", 2, "shipTo.name",   "N1", "N1", 1, "ST", null, null, null),
                new MappingRule(RuleType.LOOP_FIELD, "N3", 1, "shipTo.street", "N1", "N1", 1, "ST", null, null, null),
                new MappingRule(RuleType.LOOP_FIELD, "N4", 1, "shipTo.city",   "N1", "N1", 1, "ST", null, null, null),
                new MappingRule(RuleType.LOOP_FIELD, "N1", 2, "billTo.name",   "N1", "N1", 1, "BT", null, null, null)
        );
        Map<String, Object> out = transformer.transform(tx, rules);

        assertThat((Map<String, Object>) out.get("shipTo"))
                .containsEntry("name", "Ship To Corp")
                .containsEntry("street", "123 Ship St")
                .containsEntry("city", "Chicago");
        assertThat((Map<String, Object>) out.get("billTo")).containsEntry("name", "Bill To Corp");
    }

    @Test
    void loopField_noMatchingLoop_doesNotPopulateField() {
        var rule = new MappingRule(RuleType.LOOP_FIELD, "N1", 2, "shipTo.name",
                "N1", "N1", 1, "ST", null, null, null);

        assertThat(transformer.transform(tx(List.of(seg("BEG", 1, "00", "NE", "PO1"))), List.of(rule)))
                .doesNotContainKey("shipTo");
    }

    // ── LOOP_ARRAY ────────────────────────────────────────────────────────────

    @Test
    void loopArray_mapsEachOccurrenceToArrayItem() {
        EdiLoop line1 = loop("2000", "PO1", List.of(
                seg("PO1", 30, "1", "10", "EA", "19.99", "", "IN", "PART-001")
        ));
        EdiLoop line2 = loop("2000", "PO1", List.of(
                seg("PO1", 40, "2", "5", "EA", "49.99", "", "IN", "PART-002")
        ));
        var itemRules = List.of(
                new ItemRule("PO1", 2, "quantity"),
                new ItemRule("PO1", 4, "unitPrice"),
                new ItemRule("PO1", 7, "buyerPartNumber")
        );
        var rule = new MappingRule(RuleType.LOOP_ARRAY, null, null, null,
                "PO1", null, null, null, "lineItems", itemRules, null);

        List<Map<String, Object>> items = (List<Map<String, Object>>)
                transformer.transform(tx(List.of(line1, line2)), List.of(rule)).get("lineItems");

        assertThat(items).hasSize(2);
        assertThat(items.get(0)).containsEntry("quantity", "10").containsEntry("buyerPartNumber", "PART-001");
        assertThat(items.get(1)).containsEntry("quantity", "5");
    }

    @Test
    void loopArray_withFilter_onlyIncludesMatchingLoops() {
        // Simulates 856 flat HL structure: HL[S], HL[O], HL[I] are siblings
        EdiLoop hlS = loop("HL_S", "HL", List.of(seg("HL", 1, "1", "",  "S", "1")));
        EdiLoop hlO = loop("HL_O", "HL", List.of(seg("HL", 2, "2", "1", "O", "1"), seg("PRF", 3, "PO-777")));
        EdiLoop hlI = loop("HL_I", "HL", List.of(
                seg("HL",  4, "3", "2", "I", "0"),
                seg("LIN", 5, "", "VN", "SKU-A"),
                seg("SN1", 6, "", "10", "CA")
        ));
        EdiTransaction tx = tx(List.of(hlS, hlO, hlI));

        // Filter to only HL[I] loops (HL[3]="I")
        var rule = new MappingRule(RuleType.LOOP_ARRAY, null, null, null,
                "HL", "HL", 3, "I", "lineItems",
                List.of(new ItemRule("LIN", 3, "sku"), new ItemRule("SN1", 2, "qty")), null);

        List<Map<String, Object>> items = (List<Map<String, Object>>)
                transformer.transform(tx, List.of(rule)).get("lineItems");

        assertThat(items).hasSize(1);
        assertThat(items.get(0)).containsEntry("sku", "SKU-A").containsEntry("qty", "10");
    }

    @Test
    void loopArray_noLoopsPresent_producesEmptyArray() {
        var rule = new MappingRule(RuleType.LOOP_ARRAY, null, null, null,
                "PO1", null, null, null, "lineItems",
                List.of(new ItemRule("PO1", 2, "quantity")), null);

        assertThat((List<?>) transformer.transform(
                tx(List.of(seg("BEG", 1, "00", "NE", "PO1"))), List.of(rule)).get("lineItems")).isEmpty();
    }

    // ── LOOP_FIELD_NESTED ─────────────────────────────────────────────────────

    @Test
    void loopFieldNested_oneLevel_extractsFromInnerLoop() {
        // tx → HL[S] → N1[ST] → get N1[pos2]
        EdiLoop n1st = loop("N1_ST", "N1", List.of(seg("N1", 20, "ST", "WALMART")));
        EdiLoop hlS  = loop("HL_S",  "HL", List.of(seg("HL", 1, "1", "", "S", "1"), n1st));
        EdiTransaction tx = tx(List.of(hlS));

        var chain = List.of(
                new LoopChainEntry("HL",  null, 3, "S"),   // navigate into HL[S]
                new LoopChainEntry("N1",  null, 1, "ST")   // then into N1[ST]
        );
        var rule = new MappingRule(RuleType.LOOP_FIELD_NESTED, "N1", 2, "shipTo.name",
                null, null, null, null, null, null, chain);

        assertThat((Map<String, Object>) transformer.transform(tx, List.of(rule)).get("shipTo"))
                .containsEntry("name", "WALMART");
    }

    @Test
    void loopFieldNested_twoLevels_extractsFromDeeplyNestedLoop() {
        // tx → HL[S] → HL[O] → get PRF[pos1]
        EdiLoop hlO = loop("HL_O", "HL", List.of(
                seg("HL",  2, "2", "1", "O", "1"),
                seg("PRF", 3, "PO-99999", "", "", "20240903")
        ));
        EdiLoop hlS = loop("HL_S", "HL", List.of(
                seg("HL", 1, "1", "", "S", "1"),
                hlO
        ));
        EdiTransaction tx = tx(List.of(hlS));

        var chain = List.of(
                new LoopChainEntry("HL", null, 3, "S"),
                new LoopChainEntry("HL", null, 3, "O")
        );
        var rule = new MappingRule(RuleType.LOOP_FIELD_NESTED, "PRF", 1, "purchaseOrderNumber",
                null, null, null, null, null, null, chain);

        assertThat(transformer.transform(tx, List.of(rule))).containsEntry("purchaseOrderNumber", "PO-99999");
    }

    @Test
    void loopFieldNested_qualifiedSegmentInTargetLoop_picksCorrectRef() {
        // tx → HL[S] → find REF where REF[1]="BM" → REF[2]
        EdiLoop hlS = loop("HL_S", "HL", List.of(
                seg("HL",  1, "1", "", "S", "1"),
                seg("REF", 2, "CN", "1198629"),    // container number
                seg("REF", 3, "BM", "BOL-0003568"), // bill of lading  ← want this
                seg("REF", 4, "OQ", "7007243")     // customer order ref
        ));
        EdiTransaction tx = tx(List.of(hlS));

        var chain = List.of(new LoopChainEntry("HL", null, 3, "S"));
        var rule  = new MappingRule(RuleType.LOOP_FIELD_NESTED, "REF", 2, "billOfLading",
                null, null, 1, "BM", null, null, chain);

        assertThat(transformer.transform(tx, List.of(rule))).containsEntry("billOfLading", "BOL-0003568");
    }

    @Test
    void loopFieldNested_chainNotFound_isSkippedSilently() {
        EdiTransaction tx = tx(List.of(seg("BSN", 1, "00", "SHIP-001")));

        var rule = new MappingRule(RuleType.LOOP_FIELD_NESTED, "N1", 2, "shipFrom.name",
                null, null, null, null, null, null,
                List.of(new LoopChainEntry("HL", null, 3, "S")));

        assertThat(transformer.transform(tx, List.of(rule))).doesNotContainKey("shipFrom");
    }

    // ── LOOP_ARRAY_NESTED ─────────────────────────────────────────────────────

    @Test
    void loopArrayNested_extractsItemsFromDeeplyNestedLoop() {
        // tx → HL[S] → HL[O] → HL[T] → HL[P] → iterate all HL[I] loops
        EdiLoop hlI1 = loop("HL_I1", "HL", List.of(
                seg("HL",  5, "5", "4", "I", "0"),
                seg("LIN", 6, "", "VN", "3541W",  "UP", "199874183541"),
                seg("SN1", 7, "",  "6", "CA"),
                seg("PID", 8, "F", "", "", "", "Intensive Magnesium Relief 4oz")
        ));
        EdiLoop hlI2 = loop("HL_I2", "HL", List.of(
                seg("HL",  9,  "6", "4", "I", "0"),
                seg("LIN", 10, "", "VN", "3541X", "UP", "199874183542"),
                seg("SN1", 11, "", "12", "CA"),
                seg("PID", 12, "F", "", "", "", "Intensive Magnesium Relief 8oz")
        ));
        EdiLoop hlP = loop("HL_P", "HL", List.of(
                seg("HL", 4, "4", "3", "P", "1"),
                hlI1, hlI2
        ));
        EdiLoop hlT = loop("HL_T", "HL", List.of(
                seg("HL", 3, "3", "2", "T", "1"),
                hlP
        ));
        EdiLoop hlO = loop("HL_O", "HL", List.of(
                seg("HL",  2, "2", "1", "O", "1"),
                seg("PRF", 3, "PO-99999"),
                hlT
        ));
        EdiLoop hlS = loop("HL_S", "HL", List.of(
                seg("HL", 1, "1", "", "S", "1"),
                hlO
        ));
        EdiTransaction tx = tx(List.of(seg("BSN", 0, "00", "SHIP-001"), hlS));

        var chain = List.of(
                new LoopChainEntry("HL", null, 3, "S"),
                new LoopChainEntry("HL", null, 3, "O"),
                new LoopChainEntry("HL", null, 3, "T"),
                new LoopChainEntry("HL", null, 3, "P")
        );
        var itemRules = List.of(
                new ItemRule("LIN", 3,  "vendorItemNumber"),
                new ItemRule("LIN", 5,  "upcCode"),
                new ItemRule("SN1", 2,  "shippedQty"),
                new ItemRule("SN1", 3,  "unitOfMeasure"),
                new ItemRule("PID", 5,  "productDescription")
        );
        var rule = new MappingRule(RuleType.LOOP_ARRAY_NESTED, null, null, null,
                "HL", null, 3, "I", "lineItems", itemRules, chain);

        Map<String, Object> out = transformer.transform(tx, List.of(rule));
        List<Map<String, Object>> items = (List<Map<String, Object>>) out.get("lineItems");

        assertThat(items).hasSize(2);
        assertThat(items.get(0))
                .containsEntry("vendorItemNumber", "3541W")
                .containsEntry("upcCode", "199874183541")
                .containsEntry("shippedQty", "6")
                .containsEntry("unitOfMeasure", "CA")
                .containsEntry("productDescription", "Intensive Magnesium Relief 4oz");
        assertThat(items.get(1))
                .containsEntry("vendorItemNumber", "3541X")
                .containsEntry("shippedQty", "12");
    }

    @Test
    void loopArrayNested_noFilter_iteratesAllChildLoops() {
        // tx → HL[S] → iterate ALL N1 loops (no filterValue)
        EdiLoop n1sf = loop("N1_SF", "N1", List.of(seg("N1", 10, "SF", "Ship From")));
        EdiLoop n1st = loop("N1_ST", "N1", List.of(seg("N1", 20, "ST", "Ship To")));
        EdiLoop hlS  = loop("HL_S",  "HL", List.of(seg("HL", 1, "1", "", "S", "1"), n1sf, n1st));
        EdiTransaction tx = tx(List.of(hlS));

        var chain = List.of(new LoopChainEntry("HL", null, 3, "S"));
        var rule  = new MappingRule(RuleType.LOOP_ARRAY_NESTED, null, null, null,
                "N1", null, null, null, "parties",
                List.of(new ItemRule("N1", 1, "qualifier"), new ItemRule("N1", 2, "name")),
                chain);

        List<Map<String, Object>> parties = (List<Map<String, Object>>)
                transformer.transform(tx, List.of(rule)).get("parties");

        assertThat(parties).hasSize(2);
        assertThat(parties.get(0)).containsEntry("qualifier", "SF").containsEntry("name", "Ship From");
        assertThat(parties.get(1)).containsEntry("qualifier", "ST").containsEntry("name", "Ship To");
    }

    @Test
    void loopArrayNested_parentChainNotFound_producesNoOutput() {
        EdiTransaction tx = tx(List.of(seg("BSN", 1, "00", "SHIP-001")));

        var rule = new MappingRule(RuleType.LOOP_ARRAY_NESTED, null, null, null,
                "HL", null, 3, "I", "lineItems",
                List.of(new ItemRule("LIN", 3, "sku")),
                List.of(new LoopChainEntry("HL", null, 3, "S")));

        assertThat(transformer.transform(tx, List.of(rule))).doesNotContainKey("lineItems");
    }

    // ── Integration: full 850 mapping (regression) ───────────────────────────

    @Test
    void multipleRules_producesCompleteBusinessJson() {
        EdiLoop shipTo = loop("1000", "N1", List.of(
                seg("N1", 10, "ST", "Acme Shipping", "92", "SHIP001"),
                seg("N3", 11, "789 Freight Blvd"),
                seg("N4", 12, "Los Angeles", "CA", "90001")
        ));
        EdiLoop line1 = loop("2000", "PO1", List.of(
                seg("PO1", 20, "1", "100", "EA", "5.50", "", "", "IN", "SKU-A")
        ));
        EdiLoop line2 = loop("2000", "PO1", List.of(
                seg("PO1", 30, "2", "50", "EA", "12.00", "", "", "IN", "SKU-B")
        ));
        EdiTransaction tx = tx(List.of(
                seg("BEG", 1, "00", "NE", "PO99999", "", "20260604"),
                shipTo, line1, line2
        ));

        var rules = List.of(
                new MappingRule(RuleType.SEGMENT_FIELD, "BEG", 3, "orderNumber", null, null, null, null, null, null, null),
                new MappingRule(RuleType.SEGMENT_FIELD, "BEG", 5, "orderDate",   null, null, null, null, null, null, null),
                new MappingRule(RuleType.LOOP_FIELD, "N1", 2, "shipTo.name",   "N1", "N1", 1, "ST", null, null, null),
                new MappingRule(RuleType.LOOP_FIELD, "N3", 1, "shipTo.street", "N1", "N1", 1, "ST", null, null, null),
                new MappingRule(RuleType.LOOP_FIELD, "N4", 1, "shipTo.city",   "N1", "N1", 1, "ST", null, null, null),
                new MappingRule(RuleType.LOOP_ARRAY, null, null, null, "PO1", null, null, null, "lineItems",
                        List.of(new ItemRule("PO1", 2, "qty"), new ItemRule("PO1", 4, "price")), null)
        );

        Map<String, Object> out = transformer.transform(tx, rules);

        assertThat(out).containsEntry("orderNumber", "PO99999").containsEntry("orderDate", "20260604");
        assertThat((Map<String, Object>) out.get("shipTo"))
                .containsEntry("name", "Acme Shipping")
                .containsEntry("street", "789 Freight Blvd");
        List<Map<String, Object>> items = (List<Map<String, Object>>) out.get("lineItems");
        assertThat(items).hasSize(2);
        assertThat(items.get(0)).containsEntry("qty", "100").containsEntry("price", "5.50");
    }
}
