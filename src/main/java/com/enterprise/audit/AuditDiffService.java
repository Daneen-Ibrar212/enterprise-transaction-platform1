package com.enterprise.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;

@Service
public class AuditDiffService {

    private static final Logger log = LoggerFactory.getLogger(AuditDiffService.class);
    private final ObjectMapper objectMapper;

    public AuditDiffService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<DiffEntry> diff(String previousJson, String currentJson) {
        if (previousJson == null || currentJson == null) return List.of();
        try {
            String prev = previousJson.trim();
            String curr = currentJson.trim();
            if (prev.isEmpty() || curr.isEmpty()) return List.of();
            Map<String, Object> prevMap = objectMapper.readValue(prev, Map.class);
            Map<String, Object> currMap = objectMapper.readValue(curr, Map.class);
            return diffMaps(prevMap, currMap);
        } catch (Exception e) {
            log.error("Failed to compute diff", e);
            return List.of();
        }
    }

    private List<DiffEntry> diffMaps(Map<String, Object> prev, Map<String, Object> curr) {
        List<DiffEntry> diffs = new ArrayList<>();
        Set<String> keys = new HashSet<>(prev.keySet());
        keys.addAll(curr.keySet());

        for (String key : keys) {
            Object oldVal = prev.get(key);
            Object newVal = curr.get(key);

            if (oldVal == null && newVal == null) continue;
            if (oldVal == null) {
                diffs.add(new DiffEntry(key, "added", null, newVal));
            } else if (newVal == null) {
                diffs.add(new DiffEntry(key, "removed", oldVal, null));
            } else if (!valuesEqual(oldVal, newVal)) {
                diffs.add(new DiffEntry(key, "changed", oldVal, newVal));
            }
        }
        return diffs;
    }

    /**
     * ✅ Numeric-tolerant equality. Integer(1) == Long(1L) == BigDecimal("1.0")
     *    compares as equal. Strings that both parse to numbers also compare
     *    numerically (Jackson sometimes deserialises JSON numbers as either).
     */
    private static boolean valuesEqual(Object a, Object b) {
        if (Objects.equals(a, b)) return true;
        if (a == null || b == null) return false;

        BigDecimal na = asNumber(a);
        BigDecimal nb = asNumber(b);
        if (na != null && nb != null) return na.compareTo(nb) == 0;

        return String.valueOf(a).equals(String.valueOf(b));
    }

    private static BigDecimal asNumber(Object v) {
        if (v instanceof Number n) {
            try { return new BigDecimal(n.toString()); } catch (Exception e) { return null; }
        }
        if (v instanceof String s) {
            try { return new BigDecimal(s.trim()); } catch (Exception e) { return null; }
        }
        return null;
    }

    public static class DiffEntry {
        private final String field;
        private final String type;
        private final Object oldValue;
        private final Object newValue;

        public DiffEntry(String field, String type, Object oldValue, Object newValue) {
            this.field = field;
            this.type = type;
            this.oldValue = oldValue;
            this.newValue = newValue;
        }
        public String getField() { return field; }
        public String getType() { return type; }
        public Object getOldValue() { return oldValue; }
        public Object getNewValue() { return newValue; }
    }
}