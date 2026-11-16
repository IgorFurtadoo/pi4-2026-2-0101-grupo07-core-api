package com.lemma.core.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import io.javalin.json.JavalinJackson;

/**
 * Configuração do JSON de entrada e saída, usada pelo Main e pelos testes.
 *
 * Entrada: campo de texto só aceita texto JSON. Por padrão o Jackson converteria
 * "email": 123, 1.5 ou true em "123", "1.5" ou "true"; aqui essa conversão é
 * recusada e vira 400 REQUEST_INVALID no ErrorHandler (seção 6 do desenho).
 *
 * Saída: BigDecimal sai sempre em notação plana (toPlainString): 1 wei vira
 * 0.000000000000000001, nunca 1E-18, como no Blockchain Service.
 *
 * Os demais padrões do Javalin são mantidos.
 */
public final class JsonConfig {

    private JsonConfig() {
    }

    public static JavalinJackson jsonMapper() {
        return new JavalinJackson().updateMapper(mapper -> {
            mapper.coercionConfigFor(LogicalType.Textual)
                    .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
            mapper.enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN);
        });
    }
}
