package com.mtperaya.transactionbackend.dto;

import com.mtperaya.transactionbackend.entity.Transaction;

/**
 * Wraps the result of a transfer call, indicating whether the transaction was
 * newly created or replayed from a prior idempotency-key hit.
 */
public class TransferResult {

    private final Transaction transaction;
    private final boolean replay;

    public TransferResult(Transaction transaction, boolean replay) {
        this.transaction = transaction;
        this.replay = replay;
    }

    public Transaction getTransaction() {
        return transaction;
    }

    /** Returns {@code true} when this is an idempotency replay (200 OK expected). */
    public boolean isReplay() {
        return replay;
    }
}

