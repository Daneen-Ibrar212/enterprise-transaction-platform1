package com.enterprise.reliability;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

@Service
public class DlqProcessorService {

    private static final Logger log = LoggerFactory.getLogger(DlqProcessorService.class);

    private final DlqEntryRepository dlqEntryRepository;
    private final ObjectMapper objectMapper;
    private final Map<String, Function<Map<String, Object>, Object>> operationRegistry = new ConcurrentHashMap<>();

    public DlqProcessorService(DlqEntryRepository dlqEntryRepository, ObjectMapper objectMapper) {
        this.dlqEntryRepository = dlqEntryRepository;
        this.objectMapper = objectMapper;
    }

    public void registerOperation(String operationType, Function<Map<String, Object>, Object> operation) {
        operationRegistry.put(operationType, operation);
        log.info("Registered DLQ handler for operation: {}", operationType);
    }

    @Transactional
    public void retryDlq(Long entryId) {
        DlqEntry entry = dlqEntryRepository.findById(entryId)
                .orElseThrow(() -> new RuntimeException("DLQ entry not found"));
        if (!"PENDING".equals(entry.getStatus()) && !"RETRYING".equals(entry.getStatus())) {
            throw new IllegalStateException("Cannot retry entry with status " + entry.getStatus());
        }

        Map<String, Object> context;
        try {
            context = objectMapper.readValue(entry.getPayload(), Map.class);
        } catch (Exception e) {
            log.error("Failed to deserialize payload", e);
            entry.setStatus("FAILED");
            entry.setFailureReason("Corrupted payload: " + e.getMessage());
            dlqEntryRepository.save(entry);
            throw new RuntimeException("Corrupted DLQ entry", e);
        }

        Function<Map<String, Object>, Object> operation = operationRegistry.get(entry.getOperationType());
        if (operation == null) {
            entry.setStatus("FAILED");
            entry.setFailureReason("No handler for operation: " + entry.getOperationType());
            dlqEntryRepository.save(entry);
            throw new RuntimeException("No handler for operation: " + entry.getOperationType());
        }

        try {
            operation.apply(context);
            entry.setStatus("RESOLVED");
            dlqEntryRepository.save(entry);
            log.info("DLQ entry {} resolved successfully", entryId);
        } catch (Exception e) {
            entry.setFailureCount(entry.getFailureCount() + 1);
            entry.setFailureReason(e.getMessage());
            entry.setStatus("PENDING");
            entry.setUpdatedAt(java.time.LocalDateTime.now());
            dlqEntryRepository.save(entry);
            log.error("Retry failed for DLQ entry {} (attempt {})", entryId, entry.getFailureCount(), e);
            throw new RuntimeException("Retry failed", e);
        }
    }
}