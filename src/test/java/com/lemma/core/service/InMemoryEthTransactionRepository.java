package com.lemma.core.service;

import com.lemma.core.model.EthTransaction;
import com.lemma.core.repository.EthTransactionRepository;

import java.util.ArrayList;
import java.util.List;

/**
 * EthTransactionRepository em memória para os testes, sem banco.
 *
 * NÃO prova: o Hibernate validate de eth_transaction (CHAR(36), enums em VARCHAR,
 * DECIMAL(38,18), DATETIME(3)), a chave estrangeira para app_user nem a
 * unicidade real do task_id. Isso é provado no banco real (verificação do C5).
 */
public class InMemoryEthTransactionRepository extends EthTransactionRepository {

    public final List<EthTransaction> saved = new ArrayList<>();

    /** Quando não nulo, o save lança esta exceção (banco fora, chave repetida). */
    public RuntimeException failOnSave;

    public int saveCalls;

    public InMemoryEthTransactionRepository() {
        super(null);
    }

    @Override
    public void save(EthTransaction transaction) {
        saveCalls++;
        if (failOnSave != null) {
            throw failOnSave;
        }
        saved.add(transaction);
    }
}
