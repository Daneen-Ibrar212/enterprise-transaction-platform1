package com.enterprise.database;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Set;

@Service
public class SequenceSyncService {

    private static final Logger log = LoggerFactory.getLogger(SequenceSyncService.class);

    // ✅ Fast-path skip: tables that don't follow the `id BIGSERIAL` convention.
    // The defensive check inside syncOneTable() also handles these, but
    // skipping here saves two DB round-trips per boot.
    private static final Set<String> SKIP_TABLES = Set.of(
            "flyway_schema_history",        // Uses installed_rank
            "user_role",                    // Composite PK (user_id, role_id)
            "role_permission",              // Composite PK (role_id, permission_id)
            "idempotency_key",              // Uses `key` (String UUID) as PK, not `id`
            "spring_session",               // PK is primary_id (CHAR(36)), no `id`
            "spring_session_attributes"     // Composite PK (session_primary_id, attribute_name)
    );

    @PersistenceContext
    private EntityManager entityManager;

    private final TransactionTemplate transactionTemplate;

    public SequenceSyncService(PlatformTransactionManager transactionManager) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void syncAllSequences() {
        @SuppressWarnings("unchecked")
        List<String> tables = entityManager.createNativeQuery(
                "SELECT table_name FROM information_schema.tables " +
                "WHERE table_schema = 'public' AND table_type = 'BASE TABLE'"
        ).getResultList();

        int synced = 0, skipped = 0, failed = 0;

        for (String table : tables) {
            if (SKIP_TABLES.contains(table)) {
                log.debug("Skipping sequence sync for {}", table);
                skipped++;
                continue;
            }

            try {
                transactionTemplate.executeWithoutResult(status -> syncOneTable(table));
                synced++;
            } catch (Exception e) {
                log.warn("Failed to sync sequence for {}: {}", table, e.getMessage());
                failed++;
            }
        }

        log.info("✅ Sequence sync complete — synced: {}, skipped: {}, failed: {}",
                synced, skipped, failed);
    }

    private void syncOneTable(String table) {
        // ── 1. Does this table even have an `id` column? ──
        Object hasIdResult = entityManager.createNativeQuery(
                "SELECT EXISTS (SELECT 1 FROM information_schema.columns " +
                "WHERE table_schema = 'public' AND table_name = :table AND column_name = 'id')"
        ).setParameter("table", table).getSingleResult();

        boolean hasId = Boolean.TRUE.equals(hasIdResult);
        if (!hasId) {
            return;   // silently skip — no `id` column, nothing to sync
        }

        // ── 2. Get the sequence name (null if not a SERIAL/IDENTITY column) ──
        Object seqNameResult = entityManager.createNativeQuery(
                "SELECT pg_get_serial_sequence(:table, 'id')"
        ).setParameter("table", table).getSingleResult();

        if (seqNameResult == null) {
            return;
        }

        String seqName = seqNameResult.toString();

        // ── 3. Sync the sequence to MAX(id) ──
        Object maxIdResult = entityManager.createNativeQuery(
                "SELECT COALESCE(MAX(id), 0) FROM " + quote(table)
        ).getSingleResult();

        long maxId = ((Number) maxIdResult).longValue();
        if (maxId == 0) {
            return;
        }

        entityManager.createNativeQuery(
                "SELECT setval(:seq, :maxId, true)"
        ).setParameter("seq", seqName)
         .setParameter("maxId", maxId)
         .getSingleResult();

        log.debug("Fixed sequence for {} → {}", table, maxId);
    }

    private String quote(String identifier) {
        if (!identifier.matches("[a-z_][a-z0-9_]*")) {
            throw new IllegalArgumentException("Unsafe identifier: " + identifier);
        }
        return "\"" + identifier + "\"";
    }
}