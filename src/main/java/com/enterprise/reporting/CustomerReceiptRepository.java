package com.enterprise.reporting;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CustomerReceiptRepository extends JpaRepository<CustomerReceipt, Long> {

    List<CustomerReceipt> findByUserIdOrderByGeneratedAtDesc(Long userId);
}