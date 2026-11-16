package com.lemma.core.model;

/** Estado da transação (premissa P3, CT12). Quem decide é o Blockchain Service; aqui fica a cópia. */
public enum TransactionStatus {
    PENDING,
    CONFIRMED,
    FAILED
}
