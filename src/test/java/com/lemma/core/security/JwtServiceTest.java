package com.lemma.core.security;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * JwtService com relógio fixo: "avançar o tempo" é criar outro JwtService com o
 * mesmo segredo e um relógio mais à frente. Os tokens forjados são montados à mão
 * e, quando a regra testada vem depois da assinatura, assinados com o segredo
 * certo, para provar a regra do próprio JwtService e não só a da assinatura.
 */
class JwtServiceTest {

    /** Segredo obviamente falso, só para o teste (mínimo de 32 bytes, como na produção). */
    private static final String SECRET = "segredo-de-teste-falso-com-mais-de-32-bytes";
    private static final int TTL_MINUTES = 240;
    private static final Instant T0 = Instant.parse("2026-10-20T14:30:00Z");
    private static final long T0_SECONDS = T0.getEpochSecond();

    private static final Algorithm SAME_SECRET_HS256 = Algorithm.HMAC256(SECRET.getBytes(StandardCharsets.UTF_8));
    private static final String HS256_HEADER = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";

    private static final ObjectMapper mapper = new ObjectMapper();

    private final JwtService service = serviceAt(T0);

    private static JwtService serviceAt(Instant now) {
        return new JwtService(SECRET, TTL_MINUTES, Clock.fixed(now, ZoneOffset.UTC));
    }

    private static String base64Url(String text) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static Map<?, ?> decodePart(String part) throws Exception {
        return mapper.readValue(Base64.getUrlDecoder().decode(part), Map.class);
    }

    /** Assina "cabeçalho.payload" com HS256 e o segredo certo, sem passar pelo JwtService. */
    private static String signedRaw(String headerJson, String payloadJson) {
        String header = base64Url(headerJson);
        String payload = base64Url(payloadJson);
        byte[] signature = SAME_SECRET_HS256.sign(header.getBytes(StandardCharsets.UTF_8),
                payload.getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
    }

    /** Payload válido em tudo, menos no sub informado (JSON cru, ex.: "\"0\"" ou "1"). */
    private static String payloadWithSub(String subJson) {
        return "{\"sub\":" + subJson + ",\"iat\":" + T0_SECONDS + ",\"exp\":" + (T0_SECONDS + 3600) + "}";
    }

    private void assertRefused(String token) {
        InvalidTokenException e = assertThrows(InvalidTokenException.class, () -> service.verify(token));
        assertNull(e.getMessage());
        assertNull(e.getCause());
    }

    // ---------- emissão ----------

    @Test
    void issuedTokenIsValidAndCarriesTheUserId() {
        String token = service.issue(42).token();

        assertEquals(42L, service.verify(token));
    }

    @Test
    void expiresAtIsTheExpClaimAndIatPlusTtlTruncatedToSeconds() {
        JwtService withNanos = serviceAt(T0.plusNanos(987_654_321));

        JwtService.IssuedToken issued = withNanos.issue(7);
        DecodedJWT decoded = JWT.decode(issued.token());

        assertEquals(Instant.parse("2026-10-20T18:30:00Z"), issued.expiresAt());
        assertEquals("2026-10-20T18:30:00Z", issued.expiresAt().toString());
        assertEquals(T0, decoded.getIssuedAtAsInstant());
        assertEquals(issued.expiresAt(), decoded.getExpiresAtAsInstant());
        assertEquals(TTL_MINUTES * 60L,
                decoded.getExpiresAtAsInstant().getEpochSecond() - decoded.getIssuedAtAsInstant().getEpochSecond());
        assertEquals("7", decoded.getSubject());
    }

    @Test
    void headerAndPayloadCarryOnlyTheExpectedFields() throws Exception {
        String[] parts = service.issue(1).token().split("\\.");

        Map<?, ?> header = decodePart(parts[0]);
        Map<?, ?> payload = decodePart(parts[1]);

        assertEquals(Map.of("alg", "HS256", "typ", "JWT"), header);
        assertEquals(Set.of("sub", "iat", "exp"), payload.keySet());
        assertEquals("1", payload.get("sub"));
    }

    @Test
    void issuedTokenToStringHidesTheToken() {
        JwtService.IssuedToken issued = service.issue(1);

        assertFalse(issued.toString().contains(issued.token()));
        assertFalse(issued.toString().contains("eyJ"));
    }

    // ---------- vencimento ----------

    @Test
    void tokenIsValidOneSecondBeforeExp() {
        String token = service.issue(1).token();
        JwtService later = serviceAt(T0.plus(TTL_MINUTES, ChronoUnit.MINUTES).minusSeconds(1));

        assertEquals(1L, later.verify(token));
    }

    @Test
    void tokenIsRefusedAtExpAndAfterWithoutLeeway() {
        String token = service.issue(1).token();
        Instant exp = T0.plus(TTL_MINUTES, ChronoUnit.MINUTES);

        assertThrows(InvalidTokenException.class, () -> serviceAt(exp).verify(token));
        assertThrows(InvalidTokenException.class, () -> serviceAt(exp.plusSeconds(1)).verify(token));
        assertThrows(InvalidTokenException.class, () -> serviceAt(exp.plus(1, ChronoUnit.DAYS)).verify(token));
    }

    // ---------- assinatura e algoritmo ----------

    @Test
    void payloadChangedAfterSigningIsRefused() {
        String[] parts = service.issue(1).token().split("\\.");
        String otherUser = base64Url(payloadWithSub("\"2\""));

        assertRefused(parts[0] + "." + otherUser + "." + parts[2]);
    }

    @Test
    void changedSignatureIsRefused() {
        String token = service.issue(1).token();
        int signatureStart = token.lastIndexOf('.') + 1;
        char first = token.charAt(signatureStart);
        String tampered = token.substring(0, signatureStart) + (first == 'A' ? 'B' : 'A')
                + token.substring(signatureStart + 1);

        assertRefused(tampered);
    }

    @Test
    void tokenSignedWithAnotherSecretIsRefused() {
        JwtService other = new JwtService("outro-segredo-de-teste-falso-com-mais-de-32-bytes", TTL_MINUTES,
                Clock.fixed(T0, ZoneOffset.UTC));

        assertRefused(other.issue(1).token());
    }

    @Test
    void algNoneIsRefused() {
        String header = base64Url("{\"alg\":\"none\",\"typ\":\"JWT\"}");
        String payload = base64Url(payloadWithSub("\"1\""));
        String validSignature = service.issue(1).token().split("\\.")[2];

        assertRefused(header + "." + payload + ".");
        assertRefused(header + "." + payload + "." + validSignature);
        assertRefused(base64Url("{\"alg\":\"NONE\"}") + "." + payload + ".");
    }

    @Test
    void hs512AndHs384WithTheSameSecretAreRefused() {
        byte[] secret = SECRET.getBytes(StandardCharsets.UTF_8);

        for (Algorithm other : new Algorithm[] {Algorithm.HMAC512(secret), Algorithm.HMAC384(secret)}) {
            String token = JWT.create()
                    .withSubject("1")
                    .withIssuedAt(T0)
                    .withExpiresAt(T0.plusSeconds(3600))
                    .sign(other);
            assertRefused(token);
        }
    }

    @Test
    void headerWithoutAlgIsRefused() {
        assertRefused(signedRaw("{\"typ\":\"JWT\"}", payloadWithSub("\"1\"")));
    }

    // ---------- sub ----------

    @Test
    void validSignedRawTokenIsAccepted() {
        // Controle: o mesmo caminho dos testes abaixo, com sub válido, passa.
        assertEquals(1L, service.verify(signedRaw(HS256_HEADER, payloadWithSub("\"1\""))));
        assertEquals(Long.MAX_VALUE, service.verify(signedRaw(HS256_HEADER, payloadWithSub("\"9223372036854775807\""))));
    }

    @Test
    void missingSubIsRefused() {
        assertRefused(signedRaw(HS256_HEADER, "{\"iat\":" + T0_SECONDS + ",\"exp\":" + (T0_SECONDS + 3600) + "}"));
        assertRefused(signedRaw(HS256_HEADER, payloadWithSub("null")));
    }

    @Test
    void subThatIsNotAPositiveDecimalIntegerIsRefused() {
        String[] invalid = {"\"\"", "\"0\"", "\"-1\"", "\"+1\"", "\"1.5\"", "\"0x1\"", "\" 1\"", "\"1 \"",
                "\"abc\"", "\"01\"", "\"1e3\"", "\"١\"", "\"9223372036854775808\"", "\"99999999999999999999\""};

        for (String sub : invalid) {
            assertRefused(signedRaw(HS256_HEADER, payloadWithSub(sub)));
        }
    }

    @Test
    void subThatIsAJsonNumberIsRefused() {
        // O sub é emitido em texto; um número JSON não é aceito.
        assertRefused(signedRaw(HS256_HEADER, payloadWithSub("1")));
        assertRefused(signedRaw(HS256_HEADER, payloadWithSub("[\"1\"]")));
    }

    // ---------- exp ----------

    @Test
    void missingOrNullExpIsRefused() {
        assertRefused(signedRaw(HS256_HEADER, "{\"sub\":\"1\",\"iat\":" + T0_SECONDS + "}"));
        assertRefused(signedRaw(HS256_HEADER, "{\"sub\":\"1\",\"iat\":" + T0_SECONDS + ",\"exp\":null}"));
        assertRefused(signedRaw(HS256_HEADER, "{\"sub\":\"1\",\"iat\":" + T0_SECONDS + ",\"exp\":\"amanhã\"}"));
    }

    // ---------- lixo ----------

    @Test
    void nullEmptyAndGarbageAreRefused() {
        assertRefused(null);
        assertRefused("");
        assertRefused(" ");
        assertRefused("lixo");
        assertRefused("a.b.c");
        assertRefused("a.b");
        assertRefused("a.b.c.d");
        assertRefused("...");
        assertRefused("%%%.%%%.%%%");
    }

    @Test
    void payloadThatIsNotJsonIsRefused() {
        String header = base64Url(HS256_HEADER);

        assertRefused(header + "." + base64Url("isto não é json") + ".assinatura");
        assertRefused(signedRaw(HS256_HEADER, "isto não é json"));
        assertRefused(signedRaw("isto não é json", payloadWithSub("\"1\"")));
    }

    @Test
    void signedPayloadThatIsJsonNullOrNotAnObjectIsRefused() {
        // Passa pela assinatura; o resto da validação não pode estourar em 500.
        assertRefused(signedRaw(HS256_HEADER, "null"));
        assertRefused(signedRaw(HS256_HEADER, "[]"));
        assertRefused(signedRaw(HS256_HEADER, "\"texto\""));
        assertRefused(signedRaw(HS256_HEADER, "{}"));
    }
}
