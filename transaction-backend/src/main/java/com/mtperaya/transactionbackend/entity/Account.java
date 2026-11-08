package com.mtperaya.transactionbackend.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Version;

import org.hibernate.annotations.UuidGenerator;

import java.util.UUID;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
public class Account {

    @Id
    @UuidGenerator
    private UUID id;

    private String ownerName;

    @Column(precision = 12, scale = 2)
    private BigDecimal balance;

    @Version
    private long version;

    private LocalDateTime createdAt;

    @ManyToOne
    @JoinColumn(name = "owner_id")
    @JsonIgnore
    private User owner;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    // Getters

    public UUID getId() {
        return id;
    }

    public String getOwnerName() {
        return ownerName;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public long getVersion() {
        return version;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public User getOwner() {
        return owner;
    }

    // Setters

    public void setOwnerName(String ownerName) {
        this.ownerName = ownerName;
    }

    public void setBalance(BigDecimal balance) {
        this.balance = balance;
    }

    public void setOwner(User owner) {
        this.owner = owner;
    }
}
