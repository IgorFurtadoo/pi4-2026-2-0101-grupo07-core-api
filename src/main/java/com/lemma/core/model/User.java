package com.lemma.core.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Usuário (tabela app_user, CT2). O e-mail fica normalizado (sem espaços nas
 * pontas e em minúsculas); a senha só existe como hash bcrypt. O id é gerado pelo
 * banco (AUTO_INCREMENT). Esta entidade nunca sai pelo JSON.
 */
@Entity
@Table(name = "app_user")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "email", length = 254, nullable = false)
    private String email;

    @Column(name = "password_hash", length = 100, nullable = false)
    private String passwordHash;

    @Column(name = "ethereum_address", length = 42, nullable = false)
    private String ethereumAddress;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Exigido pelo Hibernate. */
    protected User() {
    }

    public User(String email, String passwordHash, String ethereumAddress, Instant createdAt) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.ethereumAddress = ethereumAddress;
        this.createdAt = createdAt;
    }

    /** Nulo até o usuário ser gravado. */
    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getEthereumAddress() {
        return ethereumAddress;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
