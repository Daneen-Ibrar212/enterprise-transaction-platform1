package com.enterprise.dispute;

import com.enterprise.audit.AuditService;
import com.enterprise.identity.AppUser;
import com.enterprise.identity.UserRepository;
import com.enterprise.notification.NotificationService;
import com.enterprise.tenant.TenantContext;
import com.enterprise.transaction.Transaction;
import com.enterprise.transaction.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class DisputeService {

    private static final Logger log = LoggerFactory.getLogger(DisputeService.class);

    private final DisputeRepository disputeRepository;
    private final DisputeEvidenceRepository evidenceRepository;
    private final TransactionRepository transactionRepository;
    private final NotificationService notificationService;
    private final AuditService auditService;
    private final UserRepository userRepository;

    public DisputeService(DisputeRepository disputeRepository,
                          DisputeEvidenceRepository evidenceRepository,
                          TransactionRepository transactionRepository,
                          NotificationService notificationService,
                          AuditService auditService,
                          UserRepository userRepository) {
        this.disputeRepository = disputeRepository;
        this.evidenceRepository = evidenceRepository;
        this.transactionRepository = transactionRepository;
        this.notificationService = notificationService;
        this.auditService = auditService;
        this.userRepository = userRepository;
    }

    // ============================================================
    // WHO IS CALLING?
    // ============================================================
    private boolean currentUserIsSuperAdmin() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !auth.isAuthenticated()) return false;
            return userRepository.findByEmail(auth.getName())
                    .map(AppUser::isSuperAdmin)
                    .orElse(false);
        } catch (Exception e) {
            return false;
        }
    }

    // ============================================================
    // CREATE
    // ============================================================
    @Transactional
    public Dispute create(Long transactionId, DisputeReason reason, String source, Integer respondDays) {
        Long tenantId = TenantContext.getRequiredTenantId();

        Transaction tx = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new RuntimeException("Transaction not found"));

        Dispute dispute = new Dispute();
        dispute.setTransactionId(transactionId);
        dispute.setTenantId(tenantId);
        dispute.setMerchantId(tx.getMerchantId());
        dispute.setAmount(tx.getAmount());
        dispute.setCurrency(tx.getCurrency());
        dispute.setReason(reason);
        dispute.setSource(source != null ? source : "MANUAL");
        dispute.setCustomerEmail(null);
        dispute.setStatus(DisputeStatus.OPENED);

        int days = (respondDays != null && respondDays > 0) ? respondDays : 7;
        dispute.setRespondBy(LocalDateTime.now().plusDays(days));

        Dispute saved = disputeRepository.save(dispute);

        notificationService.createNotification(
                tx.getMerchantId(),
                "DISPUTE_OPENED",
                "⚠️ Dispute Opened",
                String.format("A dispute of %s %s was opened on transaction #%d. Respond by %s.",
                        tx.getAmount().toPlainString(), tx.getCurrency(), tx.getId(),
                        saved.getRespondBy().toLocalDate()),
                "/admin/disputes/" + saved.getId()
        );

        auditService.recordEvent(
                "DISPUTE_OPENED",
                null,
                Map.of("disputeId", saved.getId(), "transactionId", transactionId,
                        "amount", tx.getAmount().toPlainString(), "reason", reason.name()),
                "Dispute",
                saved.getId(),
                null,
                null
        );

        log.info("✅ Dispute {} opened for transaction {} ({} {})",
                saved.getId(), transactionId, tx.getAmount(), tx.getCurrency());
        return saved;
    }

    // ============================================================
    // STATE TRANSITIONS
    // ============================================================
    @Transactional
    public Dispute updateStatus(Long disputeId, DisputeStatus newStatus, String notes) {
        Dispute d = get(disputeId);

        DisputeStatus old = d.getStatus();
        if (old.isTerminal()) {
            throw new IllegalStateException("Dispute already resolved as " + old);
        }

        d.setStatus(newStatus);
        d.setUpdatedAt(LocalDateTime.now());

        if (newStatus.isTerminal()) {
            d.setResolvedAt(LocalDateTime.now());
            d.setPayoutHeld(false);
            if (notes != null) d.setOutcomeNotes(notes);
        }

        Dispute saved = disputeRepository.save(d);

        auditService.recordEvent(
                "DISPUTE_" + newStatus.name(),
                null,
                Map.of("disputeId", disputeId, "from", old.name(), "to", newStatus.name(),
                        "notes", notes != null ? notes : ""),
                "Dispute",
                disputeId,
                Map.of("status", old.name()),
                Map.of("status", newStatus.name())
        );

        log.info("Dispute {} → {}", disputeId, newStatus);
        return saved;
    }

    // ============================================================
    // EVIDENCE
    // ============================================================
    @Transactional
    public DisputeEvidence addEvidence(Long disputeId, String type, String description,
                                       String filename, String contentType, byte[] content,
                                       Long submittedBy) {
        Dispute d = get(disputeId);

        if (d.getStatus().isTerminal()) {
            throw new IllegalStateException("Cannot add evidence to a resolved dispute");
        }

        DisputeEvidence ev = new DisputeEvidence();
        ev.setDisputeId(disputeId);
        ev.setTenantId(d.getTenantId());
        ev.setEvidenceType(type);
        ev.setDescription(description);
        ev.setFilename(filename);
        ev.setContentType(contentType);
        ev.setContent(content);
        ev.setSubmittedBy(submittedBy);

        DisputeEvidence saved = evidenceRepository.save(ev);

        auditService.recordEvent(
                "DISPUTE_EVIDENCE_ADDED",
                submittedBy,
                Map.of("disputeId", disputeId, "evidenceType", type, "filename",
                        filename != null ? filename : "(inline)"),
                "Dispute",
                disputeId,
                null,
                null
        );

        log.info("Evidence added to dispute {} (type={})", disputeId, type);
        return saved;
    }

    // ============================================================
    // QUERIES — Super Admin sees ALL tenants
    // ============================================================
    public List<Dispute> listForTenant() {
        if (currentUserIsSuperAdmin()) {
            return disputeRepository.findAllAcrossTenantsOrderByOpenedAtDesc();
        }
        return disputeRepository.findByTenantIdOrderByOpenedAtDesc(
                TenantContext.getRequiredTenantId());
    }

    public List<Dispute> listOpenForTenant() {
        if (currentUserIsSuperAdmin()) {
            return disputeRepository.findAllOpenAcrossTenants();
        }
        Long tenantId = TenantContext.getRequiredTenantId();
        return disputeRepository.findByTenantIdOrderByOpenedAtDesc(tenantId).stream()
                .filter(d -> d.getStatus().isOpen())
                .toList();
    }

    public Dispute get(Long id) {
        if (currentUserIsSuperAdmin()) {
            return disputeRepository.findByIdAcrossTenants(id)
                    .orElseThrow(() -> new RuntimeException("Dispute not found"));
        }
        return disputeRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Dispute not found"));
    }

    public List<DisputeEvidence> getEvidence(Long disputeId) {
        if (currentUserIsSuperAdmin()) {
            return evidenceRepository.findByDisputeIdAcrossTenantsOrderBySubmittedAtAsc(disputeId);
        }
        return evidenceRepository.findByDisputeIdOrderBySubmittedAtAsc(disputeId);
    }

    public long countOpen() {
        if (currentUserIsSuperAdmin()) {
            return disputeRepository.countAllOpen();
        }
        return disputeRepository.countOpenByTenantId(TenantContext.getRequiredTenantId());
    }

    // ============================================================
    // SCHEDULER HELPERS
    // ============================================================
    @Transactional
    public int warnUpcomingDeadlines() {
        LocalDateTime now = LocalDateTime.now();
        List<Dispute> due = disputeRepository.findDueSoon(now, now.plusDays(3));

        int warned = 0;
        for (Dispute d : due) {
            long daysLeft = d.daysUntilDeadline();
            if (daysLeft == 3 || daysLeft == 1) {
                notificationService.createNotification(
                        d.getMerchantId(),
                        "DISPUTE_DEADLINE_WARNING",
                        "⚠️ Dispute deadline approaching",
                        String.format("Dispute #%d for %s %s must be responded to within %d day(s).",
                                d.getId(), d.getAmount().toPlainString(), d.getCurrency(), daysLeft),
                        "/admin/disputes/" + d.getId()
                );
                warned++;
            }
        }
        return warned;
    }

    @Transactional
    public int autoLoseExpired() {
        LocalDateTime now = LocalDateTime.now();
        List<Dispute> expired = disputeRepository.findPastDeadline(now);

        int lost = 0;
        for (Dispute d : expired) {
            d.setStatus(DisputeStatus.LOST);
            d.setResolvedAt(now);
            d.setOutcomeNotes("Auto-lost: no response submitted before deadline");
            d.setPayoutHeld(false);
            disputeRepository.save(d);

            auditService.recordEvent(
                    "DISPUTE_AUTO_LOST",
                    null,
                    Map.of("disputeId", d.getId(), "reason", "deadline_expired"),
                    "Dispute",
                    d.getId(),
                    Map.of("status", "OPENED"),
                    Map.of("status", "LOST")
            );
            lost++;
        }
        return lost;
    }
}