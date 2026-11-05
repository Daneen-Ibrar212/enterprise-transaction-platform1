package com.enterprise.signup;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SignupRequestRepository extends JpaRepository<SignupRequest, Long> {

    List<SignupRequest> findByStatusOrderByCreatedAtDesc(SignupRequest.Status status);

    List<SignupRequest> findAllByOrderByCreatedAtDesc();

    long countByRequestedFromIpAndCreatedAtAfter(String ip, LocalDateTime since);

    boolean existsByEmailIgnoreCaseAndStatus(String email, SignupRequest.Status status);

    Optional<SignupRequest> findFirstByEmailIgnoreCaseOrderByCreatedAtDesc(String email);

    List<SignupRequest> findByStatusInOrderByCreatedAtDesc(List<SignupRequest.Status> statuses);
}