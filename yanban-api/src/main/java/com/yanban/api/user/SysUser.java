package com.yanban.api.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "sys_users")
public class SysUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String username;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "invite_code_id")
    private Long inviteCodeId;

    @Column(name = "account_type", nullable = false, length = 32)
    private String accountType = "NORMAL";

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false)
    private Instant updatedAt;

    protected SysUser() {
    }

    public SysUser(String username, String passwordHash) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.accountType = "NORMAL";
    }

    public SysUser(String username, String passwordHash, Long inviteCodeId) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.inviteCodeId = inviteCodeId;
        this.accountType = "NORMAL";
    }

    public SysUser(String username, String passwordHash, Long inviteCodeId, String accountType) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.inviteCodeId = inviteCodeId;
        this.accountType = accountType == null || accountType.isBlank() ? "NORMAL" : accountType;
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public Long getInviteCodeId() {
        return inviteCodeId;
    }

    public String getAccountType() {
        return accountType;
    }

    public void setAccountType(String accountType) {
        this.accountType = accountType == null || accountType.isBlank() ? "NORMAL" : accountType;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
