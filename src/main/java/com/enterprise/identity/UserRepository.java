package com.enterprise.identity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByEmail(String email);

    @Query("SELECT u FROM AppUser u WHERE LOWER(u.email) = LOWER(:email)")
    Optional<AppUser> findByEmailIgnoreCase(@Param("email") String email);

    @Query(value = "SELECT * FROM app_user WHERE LOWER(email) = LOWER(:email)", nativeQuery = true)
    Optional<AppUser> findByEmailIgnoreCaseGlobal(@Param("email") String email);

    List<AppUser> findByRolesName(String roleName);

    @Query("SELECT u FROM AppUser u JOIN u.roles r WHERE u.tenantId = :tenantId AND r.name = :roleName")
    List<AppUser> findByTenantIdAndRoleName(@Param("tenantId") Long tenantId, @Param("roleName") String roleName);

    List<AppUser> findByTenantId(Long tenantId);

    // Used by the admin user list to scope to the current tenant
    List<AppUser> findByTenantIdOrderByIdAsc(Long tenantId);

    @Query(value = "SELECT * FROM app_user", nativeQuery = true)
    List<AppUser> findAllWithoutTenantFilter();

    Optional<AppUser> findByEmailAndSuperAdminTrue(String email);

    List<AppUser> findByAccountLockedTrueAndLockExpiryBefore(LocalDateTime now);

    @Modifying
    @Transactional
    @Query("UPDATE AppUser u SET u.failedLoginAttempts = 0, u.accountLocked = false, u.lockExpiry = null WHERE u.id = :userId")
    void resetLockAndAttempts(@Param("userId") Long userId);

    @Modifying
    @Transactional
    @Query("UPDATE AppUser u SET u.failedLoginAttempts = :count WHERE u.id = :userId")
    void updateFailedAttempts(@Param("userId") Long userId, @Param("count") int count);

    // ============================================================
    // Tenant-scoped + filter combinations used by AdminUserController
    // ============================================================

    @Query("""
        SELECT u FROM AppUser u
         WHERE LOWER(u.email) LIKE LOWER(CONCAT('%', :search, '%'))
         ORDER BY u.id
    """)
    List<AppUser> findByEmailContainingIgnoreCase(@Param("search") String search);

    @Query("""
        SELECT u FROM AppUser u JOIN u.roles r
         WHERE r.name = :roleName
         ORDER BY u.id
    """)
    List<AppUser> findByRoleName(@Param("roleName") String roleName);

    @Query("""
        SELECT DISTINCT u FROM AppUser u JOIN u.roles r
         WHERE LOWER(u.email) LIKE LOWER(CONCAT('%', :search, '%'))
           AND r.name = :roleName
         ORDER BY u.id
    """)
    List<AppUser> findByEmailContainingIgnoreCaseAndRoleName(@Param("search") String search,
                                                             @Param("roleName") String roleName);

    @Query("""
        SELECT u FROM AppUser u
         WHERE u.tenantId = :tenantId
           AND LOWER(u.email) LIKE LOWER(CONCAT('%', :search, '%'))
         ORDER BY u.id
    """)
    List<AppUser> findByTenantIdAndEmailContainingIgnoreCase(@Param("tenantId") Long tenantId,
                                                             @Param("search") String search);

    @Query("""
        SELECT DISTINCT u FROM AppUser u JOIN u.roles r
         WHERE u.tenantId = :tenantId
           AND LOWER(u.email) LIKE LOWER(CONCAT('%', :search, '%'))
           AND r.name = :roleName
         ORDER BY u.id
    """)
    List<AppUser> findByTenantIdAndEmailContainingIgnoreCaseAndRoleName(@Param("tenantId") Long tenantId,
                                                                        @Param("search") String search,
                                                                        @Param("roleName") String roleName);
}