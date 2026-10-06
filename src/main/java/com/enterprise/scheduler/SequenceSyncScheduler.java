package com.enterprise.scheduler;

import com.enterprise.database.SequenceSyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class SequenceSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(SequenceSyncScheduler.class);
    private final SequenceSyncService sequenceSyncService;

    public SequenceSyncScheduler(SequenceSyncService sequenceSyncService) {
        this.sequenceSyncService = sequenceSyncService;
    }

    // Every hour
    @Scheduled(fixedDelay = 3600000, initialDelay = 60000)
    public void syncSequences() {
        try {
            sequenceSyncService.syncAllSequences();
        } catch (Exception e) {
            log.error("Sequence sync failed", e);
        }
    }
}