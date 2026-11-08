package com.mtperaya.transactionbackend.repository;

import com.mtperaya.transactionbackend.entity.User;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmailIgnoreCase(String email);
}
