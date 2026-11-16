package com.lemma.core.client;

/**
 * O Blockchain Service recusou o pedido de envio (CT10) com 400 e um destes codes:
 * ADDRESS_INVALID (por exemplo, checksum EIP-55 errado) ou AMOUNT_INVALID. É a
 * única recusa repassada ao usuário com o mesmo code (seção 7.1 do desenho); a
 * mensagem do outro módulo nunca é repassada. Qualquer outra falha é
 * {@link BlockchainUnavailableException}.
 */
public class BlockchainRejectedException extends RuntimeException {

    private final String code;

    public BlockchainRejectedException(String code) {
        super("CT10 requestSubmission recusado: " + code);
        this.code = code;
    }

    /** ADDRESS_INVALID ou AMOUNT_INVALID. */
    public String getCode() {
        return code;
    }
}
