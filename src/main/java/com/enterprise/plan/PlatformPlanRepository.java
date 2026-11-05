package com.enterprise.plan;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PlatformPlanRepository extends JpaRepository<PlatformPlan, Long> {

    List<PlatformPlan> findByActiveTrueOrderByDisplayOrderAsc();

    Optional<PlatformPlan> findByCode(String code);
}