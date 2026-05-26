package com.shiv.service;

import com.shiv.dtos.EdiValidationError;
import com.shiv.dtos.EdiValidationResponse;
import com.shiv.dtos.parse.*;
import com.shiv.registry.EdiSchemaRegistry;
import io.xlate.edi.schema.Schema;
import io.xlate.edi.stream.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class EdiParserService {

    private final EdiValidationService validationService;
    private final EdiDocumentDetector detector;
    private final EdiSchemaRegistry schemaRegistry;

    // AUTO: detect type, validate, then parse
    public EdiParseResult parseAuto(byte[] ediBytes) {
        EdiValidationResponse validation = validationService.validateAuto(ediBytes);
        if (isUnparseable(validation)) {
            return new EdiParseResult(false, null, validation);
        }
        return doParseSafe(ediBytes, validation);
    }

    // MANUAL: validate with given version/transaction, then parse
    public EdiParseResult parseManual(byte[] ediBytes, String version, String transaction) {
        EdiValidationResponse validation = validationService.validateManual(ediBytes, version, transaction);
        if (isUnparseable(validation)) {
            return new EdiParseResult(false, null, validation);
        }
        return doParseSafe(ediBytes, validation);
    }

    // Preflight failures and fatal errors are not parseable; schema/structural errors still allow parsing
    private boolean isUnparseable(EdiValidationResponse v) {
        return "PREFLIGHT_FAILED".equals(v.validationMode()) || "ERROR".equals(v.validationMode());
    }

    private EdiParseResult doParseSafe(byte[] ediBytes, EdiValidationResponse validation) {
        try {
            EdiDocumentDetector.EdiDocumentInfo info = detector.detect(new ByteArrayInputStream(ediBytes));
            Optional<Schema> schema = schemaRegistry.getSchema(info.version(), info.transaction());
            String mode = schema.isPresent() ? "SCHEMA_VALIDATION" : "STRUCTURAL_ONLY";
            EdiDocument document = doParse(new ByteArrayInputStream(ediBytes), schema.orElse(null), mode);
            return new EdiParseResult(true, document, validation);
        } catch (Exception e) {
            log.error("Parse step failed after validation passed: {}", e.getMessage(), e);
            EdiValidationResponse err = EdiValidationResponse.builder()
                    .valid(false).totalErrors(1).validationMode("PARSE_ERROR")
                    .summary("Parsing failed: " + e.getMessage())
                    .warnings(List.of())
                    .errors(List.of(EdiValidationError.builder()
                            .eventType("FATAL_ERROR").errorType(e.getClass().getSimpleName())
                            .segmentTag("N/A").segmentPosition(-1).elementPosition(-1).componentPosition(-1)
                            .value(e.getMessage()).build()))
                    .build();
            return new EdiParseResult(false, null, err);
        }
    }

    // =========================================================
    // CORE PARSE — stack-based hierarchical builder
    // =========================================================
    private EdiDocument doParse(ByteArrayInputStream ediStream, Schema schema, String mode)
            throws EDIStreamException {

        EDIInputFactory factory = EDIInputFactory.newFactory();
        EDIStreamReader reader = factory.createEDIStreamReader(ediStream);

        // Interchange-level
        InterchangeMeta isaMeta = new InterchangeMeta();
        List<EdiGroup> groups = new ArrayList<>();

        // Group-level
        GroupMeta gsMeta = null;
        List<EdiTransaction> currentTxList = null;

        // Transaction-level — stack tracks current loop hierarchy
        TransactionMeta stMeta = null;
        Deque<NodeContainer> nodeStack = new ArrayDeque<>();

        // Segment being accumulated
        SegmentInProgress currentSeg = null;

        // Phase flags: which envelope segment's elements we are currently reading
        boolean inIsaPhase = false;
        boolean inGsPhase  = false;

        try {
            while (reader.hasNext()) {
                EDIStreamEvent event = reader.next();
                Location loc = reader.getLocation();

                switch (event) {

                    case START_INTERCHANGE -> {
                        isaMeta = new InterchangeMeta();
                        groups.clear();
                        inIsaPhase = true;
                        log.debug("START_INTERCHANGE");
                    }

                    case START_GROUP -> {
                        inIsaPhase = false;
                        inGsPhase  = true;
                        gsMeta = new GroupMeta();
                        currentTxList = new ArrayList<>();
                        log.debug("  START_GROUP");
                    }

                    case START_TRANSACTION -> {
                        inGsPhase = false;
                        stMeta = new TransactionMeta();
                        // ST01/ST02 are captured later in END_SEGMENT when the ST segment is built
                        if (schema != null) reader.setTransactionSchema(schema); // enables START_LOOP events
                        nodeStack.clear();
                        nodeStack.push(new NodeContainer(null, 0)); // transaction root
                        log.debug("    START_TRANSACTION");
                    }

                    case START_LOOP -> {
                        String loopId = reader.getReferenceCode();
                        int occ = nodeStack.peek().nextOccurrence(loopId);
                        nodeStack.push(new NodeContainer(loopId, occ));
                        log.debug("      START_LOOP [{}] occ={}", loopId, occ);
                    }

                    case START_SEGMENT -> {
                        currentSeg = new SegmentInProgress(reader.getText(), loc.getSegmentPosition());
                        log.debug("        SEGMENT [{}] pos={}", reader.getText(), loc.getSegmentPosition());
                    }

                    case ELEMENT_DATA -> {
                        int ePos = loc.getElementPosition();
                        int cPos = loc.getComponentPosition();
                        String val = reader.getText();

                        // Route envelope element data to the correct metadata object
                        if (inIsaPhase)                        isaMeta.capture(ePos, val);
                        else if (inGsPhase && gsMeta != null)  gsMeta.capture(ePos, val);

                        // All segment elements (including ST/SE) go into the current segment builder
                        if (currentSeg != null) currentSeg.add(ePos, cPos, val);
                    }

                    case END_SEGMENT -> {
                        if (currentSeg != null && !nodeStack.isEmpty()) {
                            if ("ST".equals(currentSeg.tag)) {
                                // Capture transaction code and control number from ST01/ST02
                                // Do NOT add ST to the business tree
                                EdiSegment st = currentSeg.build();
                                if (stMeta != null) {
                                    stMeta.code = st.get(1);          // ST01 e.g. "850"
                                    stMeta.controlNumber = st.get(2); // ST02 e.g. "0001"
                                }
                                log.debug("        ST captured: code={} ctrl={}", stMeta != null ? stMeta.code : "-", stMeta != null ? stMeta.controlNumber : "-");
                            } else if (!"SE".equals(currentSeg.tag)) {
                                // All other segments except SE go into the tree
                                nodeStack.peek().children.add(currentSeg.build());
                            }
                            // SE is the transaction trailer — envelope-only, skip it
                        }
                        currentSeg = null;
                    }

                    case END_LOOP -> {
                        NodeContainer ctx = nodeStack.pop();

                        String triggerSeg = ctx.children.stream()
                                .filter(n -> n instanceof EdiSegment)
                                .map(n -> ((EdiSegment) n).tag())
                                .findFirst().orElse("");

                        Map<String, List<EdiSegment>> segIdx = ctx.children.stream()
                                .filter(n -> n instanceof EdiSegment)
                                .map(n -> (EdiSegment) n)
                                .collect(Collectors.groupingBy(EdiSegment::tag));

                        EdiLoop loop = EdiLoop.builder()
                                .loopId(ctx.loopId)
                                .occurrence(ctx.occurrence)
                                .triggerSegment(triggerSeg)
                                .segmentIndex(segIdx)
                                .children(List.copyOf(ctx.children))
                                .build();
                        if (!nodeStack.isEmpty()) nodeStack.peek().children.add(loop);
                        log.debug("      END_LOOP [{}] trigger={}", ctx.loopId, triggerSeg);
                    }

                    case END_TRANSACTION -> {
                        NodeContainer root = nodeStack.isEmpty()
                                ? new NodeContainer(null, 0) : nodeStack.pop();

                        // Index only top-level (direct-child) segments; loop-internal segments
                        // are accessible via the loop's own segmentIndex
                        Map<String, List<EdiSegment>> txSegIdx = root.children.stream()
                                .filter(n -> n instanceof EdiSegment)
                                .map(n -> (EdiSegment) n)
                                .collect(Collectors.groupingBy(EdiSegment::tag));

                        EdiTransaction tx = EdiTransaction.builder()
                                .transactionCode(str(stMeta != null ? stMeta.code : null))
                                .controlNumber(str(stMeta != null ? stMeta.controlNumber : null))
                                .segmentIndex(txSegIdx)
                                .children(List.copyOf(root.children))
                                .build();
                        if (currentTxList != null) currentTxList.add(tx);
                        log.debug("    END_TRANSACTION [{}]", tx.transactionCode());
                    }

                    case END_GROUP -> {
                        EdiGroup group = EdiGroup.builder()
                                .functionalIdentifier(str(gsMeta != null ? gsMeta.functionalId : null))
                                .applicationSender(str(gsMeta != null ? gsMeta.appSender : null))
                                .applicationReceiver(str(gsMeta != null ? gsMeta.appReceiver : null))
                                .date(str(gsMeta != null ? gsMeta.date : null))
                                .time(str(gsMeta != null ? gsMeta.time : null))
                                .controlNumber(str(gsMeta != null ? gsMeta.controlNumber : null))
                                .transactions(currentTxList != null ? List.copyOf(currentTxList) : List.of())
                                .build();
                        groups.add(group);
                        log.debug("  END_GROUP — {} transaction(s)", group.transactions().size());
                    }

                    case END_INTERCHANGE -> log.debug("END_INTERCHANGE — {} group(s)", groups.size());

                    default -> { /* SEGMENT_ERROR, ELEMENT_DATA_ERROR — validation already captured these */ }
                }
            }

        } finally {
            try { reader.close(); } catch (Exception ignored) {}
        }

        EdiInterchange interchange = EdiInterchange.builder()
                .senderId(str(isaMeta.senderId))
                .receiverId(str(isaMeta.receiverId))
                .date(str(isaMeta.date))
                .time(str(isaMeta.time))
                .version(str(isaMeta.version))
                .controlNumber(str(isaMeta.controlNumber))
                .groups(List.copyOf(groups))
                .build();

        String docType = groups.stream()
                .flatMap(g -> g.transactions().stream())
                .map(EdiTransaction::transactionCode)
                .findFirst().orElse("UNKNOWN");

        log.info("Parsed EDI: type={} version={} groups={} loopsResolved={}",
                docType, isaMeta.version, groups.size(), schema != null);

        return EdiDocument.builder()
                .documentType(docType)
                .version(str(isaMeta.version))
                .senderId(str(isaMeta.senderId))
                .receiverId(str(isaMeta.receiverId))
                .validationMode(mode)
                .loopsResolved(schema != null)
                .parsedAt(Instant.now())
                .interchange(interchange)
                .build();
    }

    private static String str(String val) {
        return val != null ? val : "";
    }

    // =========================================================
    // Internal build-time state — NOT part of the output model
    // =========================================================

    private static class InterchangeMeta {
        String senderId, receiverId, date, time, version, controlNumber;

        void capture(int pos, String val) {
            switch (pos) {
                case 6  -> senderId      = val.trim();
                case 8  -> receiverId    = val.trim();
                case 9  -> date          = val;
                case 10 -> time          = val;
                case 12 -> version       = val;
                case 13 -> controlNumber = val;
            }
        }
    }

    private static class GroupMeta {
        String functionalId, appSender, appReceiver, date, time, controlNumber;

        void capture(int pos, String val) {
            switch (pos) {
                case 1 -> functionalId  = val;
                case 2 -> appSender     = val;
                case 3 -> appReceiver   = val;
                case 4 -> date          = val;
                case 5 -> time          = val;
                case 6 -> controlNumber = val;
            }
        }
    }

    private static class TransactionMeta {
        String code, controlNumber;
        // Populated from the ST segment in END_SEGMENT (not from getReferenceCode())
    }

    /** Represents either the transaction root or a loop being accumulated on the stack. */
    private static class NodeContainer {
        final String loopId;   // null for transaction root
        final int occurrence;
        final List<EdiNode> children = new ArrayList<>();
        final Map<String, Integer> childLoopCounts = new HashMap<>();

        NodeContainer(String loopId, int occurrence) {
            this.loopId = loopId;
            this.occurrence = occurrence;
        }

        /** Increments and returns the occurrence counter for a child loop. */
        int nextOccurrence(String childLoopId) {
            return childLoopCounts.merge(childLoopId, 1, Integer::sum);
        }
    }

    /** Accumulates elements for the segment currently being read. */
    private static class SegmentInProgress {
        final String tag;
        final int position;
        final TreeMap<Integer, ElementInProgress> elements = new TreeMap<>();

        SegmentInProgress(String tag, int position) {
            this.tag = tag;
            this.position = position;
        }

        void add(int ePos, int cPos, String val) {
            if (cPos == 0) {
                // Simple element
                elements.put(ePos, new ElementInProgress(ePos, val));
            } else {
                // Component of a composite element
                elements.computeIfAbsent(ePos, k -> new ElementInProgress(k, null))
                        .addComponent(cPos, val);
            }
        }

        EdiSegment build() {
            List<EdiElement> elems = elements.values().stream()
                    .map(ElementInProgress::build)
                    .collect(Collectors.toList());
            return EdiSegment.builder().tag(tag).position(position).elements(elems).build();
        }
    }

    /** Accumulates components for a single element (simple or composite). */
    private static class ElementInProgress {
        final int position;
        final String simpleValue;                        // set for simple elements (cPos == 0)
        final TreeMap<Integer, String> components = new TreeMap<>(); // set for composites (cPos > 0)

        ElementInProgress(int position, String simpleValue) {
            this.position = position;
            this.simpleValue = simpleValue;
        }

        void addComponent(int cPos, String val) {
            components.put(cPos, val);
        }

        EdiElement build() {
            if (components.isEmpty()) {
                // StAEDI reported componentPosition=0 → genuinely simple element
                return EdiElement.builder()
                        .position(position)
                        .value(simpleValue != null ? simpleValue : "")
                        .components(List.of())
                        .build();
            }
            List<String> comps = List.copyOf(components.values());
            if (comps.size() == 1) {
                // StAEDI reported componentPosition=1 for what is effectively a simple value.
                // This happens when a schema defines a single-component composite element.
                // Normalize to a plain element so API consumers never see components:["x"].
                return EdiElement.builder()
                        .position(position)
                        .value(comps.get(0))
                        .components(List.of())
                        .build();
            }
            // True composite: 2+ components (e.g. a date/time pair, or a product/service ID composite)
            return EdiElement.builder()
                    .position(position)
                    .value(String.join(":", comps))
                    .components(comps)
                    .build();
        }
    }
}