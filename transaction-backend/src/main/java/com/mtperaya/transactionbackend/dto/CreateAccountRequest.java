package com.mtperaya.transactionbackend.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;

public class CreateAccountRequest {

    @NotBlank
    private String ownerName;
    
    @NotNull
    @DecimalMin(value = "0.00")
    private BigDecimal initialBalance;

    public CreateAccountRequest() {
    }

    public String getOwnerName() {
        return ownerName;
    }

    public void setOwnerName(String ownerName) {
        this.ownerName = ownerName;
    }

    public BigDecimal getInitialBalance() {
        return initialBalance;
    }

    public void setInitialBalance(BigDecimal initialBalance) {
        this.initialBalance = initialBalance;
    }
}
