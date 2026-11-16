package com.lemma.core.repository;

import com.lemma.core.model.EthTransaction;
import org.hibernate.SessionFactory;

/**
 * Acesso à tabela eth_transaction (CT12). Cada método abre e fecha a própria
 * transação (seção 4.2 do desenho). Não loga nada; quem chama decide o que vai
 * ao log. As consultas e a atualização condicional entram no passo C6.
 */
public class EthTransactionRepository {

    private final SessionFactory sessionFactory;

    public EthTransactionRepository(SessionFactory sessionFactory) {
        this.sessionFactory = sessionFactory;
    }

    /** Grava a transação. Qualquer falha (banco fora, taskId repetido) sobe como está. */
    public void save(EthTransaction transaction) {
        sessionFactory.inTransaction(session -> session.persist(transaction));
    }
}
