package com.stellartrack.auth.application;

import com.stellartrack.audit.application.AuditTrail;
import com.stellartrack.audit.domain.AuditAction;
import com.stellartrack.audit.domain.AuditRecord;
import com.stellartrack.auth.persistence.AdminUserEntity;
import com.stellartrack.auth.persistence.AdminUserRepository;
import com.stellartrack.common.exception.ConflictException;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handles first-time administrator registration via the API.
 * Admin accounts are created through {@code POST /api/v1/auth/register} and only
 * when no admin exists yet.
 */
@Component
public class AdminProvisioningService {

    private static final Logger log = LoggerFactory.getLogger(AdminProvisioningService.class);

    private final AdminUserRepository admins;
    private final PasswordEncoder passwordEncoder;
    private final AuditTrail auditTrail;
    private final Clock clock;

    public AdminProvisioningService(AdminUserRepository admins, PasswordEncoder passwordEncoder,
            AuditTrail auditTrail, Clock clock) {
        this.admins = admins;
        this.passwordEncoder = passwordEncoder;
        this.auditTrail = auditTrail;
        this.clock = clock;
    }

    /**
     * Registers the first administrator via the API. Fails with 409 if any admin already exists,
     * ensuring this endpoint is only effective for first-time setup.
     */
    @Transactional
    public AdminUserEntity registerFirstAdmin(String username, String password) {
        if (admins.count() > 0) {
            throw new ConflictException("ADMIN_ALREADY_EXISTS", "An administrator account already exists.");
        }
        PasswordPolicy.validate(password);
        AdminUserEntity admin = AdminUserEntity.create(username, passwordEncoder.encode(password), clock.instant());
        admins.save(admin);
        auditTrail.record(AuditRecord.system(AuditAction.ADMIN_REGISTERED).entity("admin_user", admin.getId()));
        log.info("Administrator account registered via API");
        return admin;
    }
}
