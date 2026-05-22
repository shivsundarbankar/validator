package com.shiv.service;

import io.xlate.edi.stream.EDIInputFactory;
import io.xlate.edi.stream.EDIStreamEvent;
import io.xlate.edi.stream.EDIStreamException;
import io.xlate.edi.stream.EDIStreamReader;
import io.xlate.edi.stream.Location;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;

@Slf4j
@Component
public class EdiDocumentDetector {

    public EdiDocumentInfo detect(InputStream ediStream) throws EDIStreamException, IOException {
        EDIInputFactory factory = EDIInputFactory.newFactory();
        EDIStreamReader reader = factory.createEDIStreamReader(ediStream);

        String version = null;
        String transaction = null;
        String senderId = null;
        String receiverId = null;

        // ISA element positions we care about:
        // ISA06 = Sender ID        (element position 6)
        // ISA08 = Receiver ID      (element position 8)
        // ISA12 = Version          (element position 12)
        // ST01  = Transaction code (element position 1 inside ST segment)

        boolean inISA = false;
        boolean inST = false;
        int elementPos = 0;

        try {
            while (reader.hasNext()) {
                EDIStreamEvent event = reader.next();
                Location loc = reader.getLocation();

                switch (event) {

                    case START_SEGMENT -> {
                        String tag = reader.getText();
                        inISA = "ISA".equals(tag);
                        inST = "ST".equals(tag);
                        elementPos = 0; // reset element counter per segment
                    }

                    case END_SEGMENT -> {
                        inISA = false;
                        inST = false;
                        // Stop after ST — we have everything
                        if (transaction != null) return buildInfo(version, transaction, senderId, receiverId);
                    }

                    case ELEMENT_DATA -> {
                        elementPos = loc.getElementPosition();
                        String val = reader.getText();

                        if (inISA) {
                            switch (elementPos) {
                                case 6 -> senderId = val.trim(); // ISA06 — trim padding spaces
                                case 8 -> receiverId = val.trim(); // ISA08 — trim padding spaces
                                case 12 -> version = normalizeVersion(val); // ISA12
                            }
                        }

                        if (inST && elementPos == 1) {
                            transaction = val.trim(); // ST01 = "850", "810" etc.
                        }
                    }

                    default -> {
                    }
                }
            }
        } finally {
            reader.close();
        }

        return buildInfo(version, transaction, senderId, receiverId);
    }

    private EdiDocumentInfo buildInfo(String version, String transaction, String senderId, String receiverId) {
        if (version == null || transaction == null) {
            throw new IllegalArgumentException("Could not detect version [" + version + "] or transaction [" + transaction + "] — " + "check ISA and ST segments are present.");
        }
        log.info("Detected → version={} transaction={} sender={} receiver={}", version, transaction, senderId, receiverId);
        return new EdiDocumentInfo(version, transaction, senderId, receiverId);
    }

    // ISA12 is 5 chars (00401), normalize to 6 chars (004010)
    private String normalizeVersion(String isaVersion) {
        if (isaVersion == null) return null;
        return switch (isaVersion.trim()) {
            case "00401" -> "004010";
            case "00501" -> "005010";
            case "00402" -> "004020";
            case "00601" -> "006010";
            case "00701" -> "007010";
            default -> isaVersion.trim();
        };
    }

    public record EdiDocumentInfo(String version,      // e.g. "004010"
                                  String transaction,  // e.g. "850"
                                  String senderId,     // ISA06
                                  String receiverId    // ISA08
    ) {
    }
}