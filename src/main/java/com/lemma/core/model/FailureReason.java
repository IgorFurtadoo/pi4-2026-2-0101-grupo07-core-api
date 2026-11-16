package com.lemma.core.model;

/** Motivo da falha (CT12): só existe quando o estado é FAILED. */
public enum FailureReason {
    REJECTED_BY_NETWORK,
    TRANSACTION_REVERTED,
    INTERNAL_ERROR
}
