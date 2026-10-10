package com.enterprise.reconciliation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface ReconciliationDetailRepository extends JpaRepository<ReconciliationDetail, Long> {

    List<ReconciliationDetail> findByReconciliationRecordId(Long recordId);

    List<ReconciliationDetail> findByReconciliationRecordIdAndDiscrepancyType(Long recordId, String discrepancyType);

    List<ReconciliationDetail> findByReconciliationRecordIdAndRepairStatus(Long recordId, String repairStatus);

    @Query("SELECT d FROM ReconciliationDetail d WHERE d.repairStatus = 'PENDING'")
    List<ReconciliationDetail> findAllPendingRepairs();
}