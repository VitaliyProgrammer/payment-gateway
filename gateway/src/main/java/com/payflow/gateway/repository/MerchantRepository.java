package com.payflow.gateway.repository;

import java.util.Optional;
import java.util.UUID;

import com.payflow.gateway.entity.Merchant;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MerchantRepository extends JpaRepository<Merchant, UUID> {

    Optional<Merchant> findByApiKeyHash(String apiKeyHash);
}
