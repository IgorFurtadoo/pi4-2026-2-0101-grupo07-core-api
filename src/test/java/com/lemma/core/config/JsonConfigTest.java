package com.lemma.core.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JacksonException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Prova a leitura do corpo (campo de texto só aceita texto JSON, campo ausente
 * fica nulo e campo extra é ignorado) e a escrita de BigDecimal em notação plana.
 */
class JsonConfigTest {

    /** Corpo de teste com dois campos de texto, como o do CT1. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record TextBody(String email, String password) {
    }

    private static TextBody read(String json) {
        return JsonConfig.jsonMapper().fromJsonString(json, TextBody.class);
    }

    @Test
    void textFieldsAreRead() {
        TextBody body = read("{\"email\":\"ana@example.com\",\"password\":\"Senha@12345\"}");

        assertEquals("ana@example.com", body.email());
        assertEquals("Senha@12345", body.password());
    }

    @Test
    void integerInTextFieldIsRefused() {
        assertThrows(JacksonException.class, () -> read("{\"email\":123}"));
    }

    @Test
    void floatInTextFieldIsRefused() {
        assertThrows(JacksonException.class, () -> read("{\"email\":1.5}"));
    }

    @Test
    void booleanInTextFieldIsRefused() {
        assertThrows(JacksonException.class, () -> read("{\"password\":true}"));
    }

    @Test
    void objectAndArrayInTextFieldAreRefused() {
        assertThrows(JacksonException.class, () -> read("{\"email\":{\"a\":1}}"));
        assertThrows(JacksonException.class, () -> read("{\"email\":[\"a\"]}"));
    }

    @Test
    void missingFieldsAreNullAndExtraFieldsAreIgnored() {
        TextBody body = read("{\"email\":\"ana@example.com\",\"extra\":true}");

        assertEquals("ana@example.com", body.email());
        assertNull(body.password());
    }

    @Test
    void explicitNullIsNull() {
        TextBody body = read("{\"email\":null,\"password\":null}");

        assertNull(body.email());
        assertNull(body.password());
    }

    // ---------- saída: BigDecimal em notação plana ----------

    /** Corpo de saída com um decimal, como o do CT6. */
    record DecimalBody(BigDecimal balanceEth) {
    }

    private static String write(String decimal) {
        return JsonConfig.jsonMapper().toJsonString(new DecimalBody(new BigDecimal(decimal)), DecimalBody.class);
    }

    @Test
    void oneWeiIsWrittenPlainNeverWithExponent() {
        assertEquals("{\"balanceEth\":0.000000000000000001}", write("1E-18"));
        assertEquals("{\"balanceEth\":0.000000000000000001}", write("0.000000000000000001"));
    }

    @Test
    void zeroAndCommonValuesAreWrittenAsTheyAre() {
        assertEquals("{\"balanceEth\":0}", write("0"));
        assertEquals("{\"balanceEth\":0.25}", write("0.25"));
        assertEquals("{\"balanceEth\":0.000000000000000000}", write("0E-18"));
        assertEquals("{\"balanceEth\":123456789.123456789012345678}", write("123456789.123456789012345678"));
    }

    @Test
    void largeValueWithPositiveExponentIsWrittenPlain() {
        assertEquals("{\"balanceEth\":100}", write("1E+2"));
    }
}
