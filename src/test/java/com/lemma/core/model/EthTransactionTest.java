package com.lemma.core.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A transação nova (CT9 → CT12) nasce PENDING, sem hash e sem motivo, com as duas
 * datas iguais. O mapeamento para o banco (validate) só se prova no MySQL real.
 */
class EthTransactionTest {

    @Test
    void newTransactionIsPendingWithoutHashOrReasonAndEqualDates() {
        Instant now = Instant.parse("2026-10-20T14:35:00Z");

        EthTransaction transaction = new EthTransaction("a3f1c2d4-5b6e-4f70-8a91-0c2d3e4f5a6b", 1L,
                "0x5B38Da6a701c568545dCfcB03FcB875f56beddC4", new BigDecimal("0.01"), now);

        assertEquals("a3f1c2d4-5b6e-4f70-8a91-0c2d3e4f5a6b", transaction.getTaskId());
        assertEquals(1L, transaction.getUserId());
        assertEquals("0x5B38Da6a701c568545dCfcB03FcB875f56beddC4", transaction.getDestinationAddress());
        assertEquals(new BigDecimal("0.01"), transaction.getAmountEth());
        assertEquals(TransactionStatus.PENDING, transaction.getStatus());
        assertNull(transaction.getTransactionHash());
        assertNull(transaction.getFailureReason());
        assertEquals(now, transaction.getCreatedAt());
        assertEquals(now, transaction.getUpdatedAt());
    }
}
