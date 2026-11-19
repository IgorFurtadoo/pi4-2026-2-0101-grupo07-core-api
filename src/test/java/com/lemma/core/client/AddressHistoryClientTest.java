package com.lemma.core.client;

import com.lemma.core.LogCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.lemma.core.client.FakeExternalDataService.COUNTERPARTY;
import static com.lemma.core.client.FakeExternalDataService.hash;
import static com.lemma.core.client.FakeExternalDataService.historyBody;
import static com.lemma.core.client.FakeExternalDataService.historyItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testa o CT24 fetchAddressTransactions contra o External Data Service falso
 * (servidor HTTP do próprio JDK), sem rede externa: requisição, conferência do
 * corpo 200, tradução dos erros e o que vai ao log.
 */
class AddressHistoryClientTest {

    private static final String SERVICE_KEY = "chave-de-servico-de-teste-falsa";
    private static final String ADDRESS = "0xbdd26D8f9B9876D59CD6ebE1F25a1286b8ECaCD5";
    private static final Duration TIMEOUT = Duration.ofSeconds(1);
    private static final String REFUSED_LOG =
            "WARN CT24 fetchAddressTransactions: HTTP 200 com corpo fora do formato";

    private FakeExternalDataService fake;

    @BeforeEach
    void startFake() {
        fake = new FakeExternalDataService(SERVICE_KEY);
    }

    @AfterEach
    void stopFake() {
        fake.close();
    }

    private AddressHistoryClient client() {
        return new AddressHistoryClient(fake.baseUrl(), SERVICE_KEY, TIMEOUT);
    }

    private List<AddressTransaction> fetch() {
        return client().fetchAddressTransactions(ADDRESS);
    }

    /** O log nunca traz corpo, chave, URL, porta nem endereço. */
    private void assertLogClean(String log) {
        assertFalse(log.contains("mensagem-secreta-do-corpo"), log);
        assertFalse(log.contains(SERVICE_KEY), log);
        assertFalse(log.contains("127.0.0.1"), log);
        assertFalse(log.contains(fake.baseUrl().substring(fake.baseUrl().lastIndexOf(':') + 1)), log);
        assertFalse(log.contains("addresses"), log);
        assertFalse(log.toLowerCase().contains(ADDRESS.toLowerCase()), log);
    }

    /** O 200 com este corpo é recusado, o log diz só "fora do formato" e houve uma requisição. */
    private void assertBodyRefused(String body) {
        fake.reply(200, body);
        int before = fake.requests().size();
        try (LogCapture logs = new LogCapture(AddressHistoryClient.class)) {
            assertThrows(HistoryUnavailableException.class, this::fetch, body);
            assertEquals(REFUSED_LOG, logs.all(), body);
            assertLogClean(logs.all());
        }
        assertEquals(before + 1, fake.requests().size(), body);
    }

    /** Corpo com um item válido seguido do item dado: o item ruim invalida a lista toda. */
    private static String withItem(String itemJson) {
        return historyBody(ADDRESS, "[" + historyItem(1, "0.0005") + "," + itemJson + "]");
    }

    /** Item com todos os campos válidos, trocando um campo pelo JSON dado (ou removendo, se null). */
    private static String itemWith(String field, String valueJson) {
        StringBuilder json = new StringBuilder("{");
        String[][] fields = {
                {"hash", "\"" + hash(2) + "\""},
                {"direction", "\"SENT\""},
                {"counterpartyAddress", "\"" + COUNTERPARTY + "\""},
                {"amountEth", "\"0.01\""},
                {"timestamp", "\"2026-11-15T10:00:00Z\""},
                {"status", "\"CONFIRMED\""}};
        for (String[] f : fields) {
            String value = f[0].equals(field) ? valueJson : f[1];
            if (value == null) {
                continue;
            }
            json.append(json.length() == 1 ? "" : ",").append('"').append(f[0]).append("\":").append(value);
        }
        return json.append('}').toString();
    }

    // ==================== requisição ====================

    @Test
    void isASingleGetToTheAddressPathWithServiceKeyAndNoBody() {
        fake.reply(200, historyBody(ADDRESS, "[]"));

        fetch();

        assertEquals(List.of(new FakeExternalDataService.Received(
                        "GET", "/addresses/" + ADDRESS + "/transactions", null, true, 0)),
                fake.requests());
    }

    @Test
    void theFakeNoticesAWrongServiceKey() {
        fake.reply(200, historyBody(ADDRESS, "[]"));

        new AddressHistoryClient(fake.baseUrl(), "outra-chave-falsa", TIMEOUT).fetchAddressTransactions(ADDRESS);

        assertFalse(fake.requests().get(0).serviceKeyOk());
    }

    @Test
    void connectTimeoutIsThreeSeconds() {
        assertEquals(Duration.ofSeconds(3), AddressHistoryClient.CONNECT_TIMEOUT);
    }

    // ==================== 200 válido ====================

    @Test
    void validBodyComesBackExactlyAndInOrder() {
        fake.reply(200, historyBody(ADDRESS, "[" + historyItem(1, "0.0005") + ","
                + "{\"hash\":\"" + hash(2) + "\",\"direction\":\"SENT\",\"counterpartyAddress\":null,"
                + "\"amountEth\":\"0\",\"timestamp\":\"2026-11-15T10:00:00Z\",\"status\":\"FAILED\"},"
                + "{\"hash\":\"" + hash(3) + "\",\"direction\":\"SELF\",\"counterpartyAddress\":\"" + ADDRESS + "\","
                + "\"amountEth\":\"0.000000000000000001\",\"timestamp\":\"2026-11-14T09:00:00Z\","
                + "\"status\":\"CONFIRMED\"}]"));

        assertEquals(List.of(
                new AddressTransaction(hash(1), "RECEIVED", COUNTERPARTY, new BigDecimal("0.0005"),
                        Instant.parse("2026-11-16T04:12:00Z"), "CONFIRMED"),
                new AddressTransaction(hash(2), "SENT", null, new BigDecimal("0"),
                        Instant.parse("2026-11-15T10:00:00Z"), "FAILED"),
                new AddressTransaction(hash(3), "SELF", ADDRESS, new BigDecimal("0.000000000000000001"),
                        Instant.parse("2026-11-14T09:00:00Z"), "CONFIRMED")), fetch());
    }

    @Test
    void amountsComeBackExactly() {
        for (String value : List.of("0", "1", "0.0005", "0.000000000000000001", "123456789.123456789012345678",
                "99999999999999999999.999999999999999999", "0.50")) {
            fake.reply(200, historyBody(ADDRESS, "[" + historyItem(1, value) + "]"));

            // BigDecimal.equals compara valor e escala: o valor sai como veio.
            assertEquals(new BigDecimal(value), fetch().get(0).amountEth(), value);
        }
    }

    @Test
    void emptyListIsValid() {
        fake.reply(200, historyBody(ADDRESS, "[]"));

        assertEquals(List.of(), fetch());
    }

    @Test
    void twentyItemsAreAccepted() {
        List<String> items = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            items.add(historyItem(i, "0.0" + (i + 1)));
        }
        fake.reply(200, historyBody(ADDRESS, "[" + String.join(",", items) + "]"));

        List<AddressTransaction> transactions = fetch();

        assertEquals(20, transactions.size());
        assertEquals(new BigDecimal("0.01"), transactions.get(0).amountEth());
        assertEquals(new BigDecimal("0.020"), transactions.get(19).amountEth());
    }

    @Test
    void addressInTheBodyIsComparedIgnoringCase() {
        for (String echoed : List.of(ADDRESS, ADDRESS.toLowerCase(), "0x" + ADDRESS.substring(2).toUpperCase())) {
            fake.reply(200, historyBody(echoed, "[" + historyItem(1, "0.0005") + "]"));

            assertEquals(1, fetch().size(), echoed);
        }
    }

    @Test
    void timestampWithOffsetOrFractionIsReadAsTheSameInstant() {
        fake.reply(200, historyBody(ADDRESS, "[" + itemWith("timestamp", "\"2026-11-16T05:12:00+01:00\"") + ","
                + itemWith("timestamp", "\"2026-11-16T04:12:00.000000Z\"") + "]"));

        List<AddressTransaction> transactions = fetch();

        assertEquals(Instant.parse("2026-11-16T04:12:00Z"), transactions.get(0).timestamp());
        assertEquals(Instant.parse("2026-11-16T04:12:00Z"), transactions.get(1).timestamp());
    }

    @Test
    void extraFieldsAreIgnored() {
        fake.reply(200, "{\"ethereumAddress\":\"" + ADDRESS + "\",\"network\":\"SEPOLIA\",\"fonte\":\"x\","
                + "\"transactions\":[" + itemWith("status", "\"CONFIRMED\",\"bloco\":123") + "]}");

        assertEquals(1, fetch().size());
    }

    @Test
    void successIsNotLogged() {
        fake.reply(200, historyBody(ADDRESS, "[" + historyItem(1, "0.0005") + "]"));

        try (LogCapture logs = new LogCapture(AddressHistoryClient.class)) {
            fetch();
            assertEquals("", logs.all());
        }
    }

    // ==================== endereço fora do formato: nenhuma chamada ====================

    @Test
    void addressOutOfFormatMakesNoCall() {
        fake.reply(200, historyBody(ADDRESS, "[]"));
        AddressHistoryClient client = client();

        for (String address : Arrays.asList(null, "", "0x123", ADDRESS + "0", ADDRESS.substring(0, 41),
                "0X" + ADDRESS.substring(2), ADDRESS.substring(2), "0x" + "g".repeat(40),
                "0x../" + "a".repeat(36), "0x" + "a".repeat(39) + "/", "0x" + "a".repeat(39) + "?",
                "0x" + "a".repeat(39) + "%", ADDRESS + "\n")) {
            try (LogCapture logs = new LogCapture(AddressHistoryClient.class)) {
                assertThrows(HistoryUnavailableException.class, () -> client.fetchAddressTransactions(address),
                        address);
                assertEquals("ERROR CT24 fetchAddressTransactions: endereço fora do formato; a chamada não foi feita",
                        logs.all(), address);
            }
        }

        assertTrue(fake.requests().isEmpty());
    }

    // ==================== 200 fora do formato ====================

    @Test
    void rootThatIsNotAnObjectEmptyOrUnreadableIsRefused() {
        assertBodyRefused("");
        assertBodyRefused("[]");
        assertBodyRefused("123");
        assertBodyRefused("\"x\"");
        assertBodyRefused("null");
        assertBodyRefused("isto não é JSON mensagem-secreta-do-corpo");
        assertBodyRefused("{\"ethereumAddress\":\"" + ADDRESS + "\",");
    }

    @Test
    void addressDifferentMissingOrNotTextIsRefused() {
        assertBodyRefused(historyBody("0x71C7656EC7ab88b098defB751B7401B5f6d8976F", "[]"));
        assertBodyRefused(historyBody(ADDRESS + " ", "[]"));
        assertBodyRefused("{\"network\":\"SEPOLIA\",\"transactions\":[]}");
        assertBodyRefused("{\"ethereumAddress\":null,\"network\":\"SEPOLIA\",\"transactions\":[]}");
        assertBodyRefused("{\"ethereumAddress\":1,\"network\":\"SEPOLIA\",\"transactions\":[]}");
    }

    @Test
    void networkNotExactlySepoliaOrMissingIsRefused() {
        for (String network : List.of("\"MAINNET\"", "\"sepolia\"", "\"SEPOLIA \"", "null", "1")) {
            assertBodyRefused("{\"ethereumAddress\":\"" + ADDRESS + "\",\"network\":" + network
                    + ",\"transactions\":[]}");
        }
        assertBodyRefused("{\"ethereumAddress\":\"" + ADDRESS + "\",\"transactions\":[]}");
    }

    @Test
    void transactionsMissingNotAListOrLongerThanTwentyIsRefused() {
        assertBodyRefused("{\"ethereumAddress\":\"" + ADDRESS + "\",\"network\":\"SEPOLIA\"}");
        assertBodyRefused(historyBody(ADDRESS, "null"));
        assertBodyRefused(historyBody(ADDRESS, "{}"));
        assertBodyRefused(historyBody(ADDRESS, "\"x\""));

        List<String> items = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            items.add(historyItem(i, "0.01"));
        }
        assertBodyRefused(historyBody(ADDRESS, "[" + String.join(",", items) + "]"));
    }

    @Test
    void anItemThatIsNotAnObjectInvalidatesTheWholeBody() {
        assertBodyRefused(withItem("1"));
        assertBodyRefused(withItem("null"));
        assertBodyRefused(withItem("\"x\""));
        assertBodyRefused(withItem("[]"));
    }

    @Test
    void invalidHashInvalidatesTheWholeBody() {
        for (String value : Arrays.asList(null, "null", "1", "\"0x123\"", "\"" + hash(1) + "0\"",
                "\"" + hash(1).substring(2) + "\"", "\"0x" + "g".repeat(64) + "\"")) {
            assertBodyRefused(withItem(itemWith("hash", value)));
        }
    }

    @Test
    void invalidDirectionInvalidatesTheWholeBody() {
        for (String value : Arrays.asList(null, "null", "1", "\"sent\"", "\"IN\"", "\"SENT \"", "\"\"")) {
            assertBodyRefused(withItem(itemWith("direction", value)));
        }
    }

    @Test
    void counterpartyMissingOrInvalidInvalidatesTheWholeBody() {
        for (String value : Arrays.asList(null, "1", "\"0x123\"", "\"\"", "\"" + COUNTERPARTY + "0\"",
                "[\"" + COUNTERPARTY + "\"]")) {
            assertBodyRefused(withItem(itemWith("counterpartyAddress", value)));
        }
    }

    @Test
    void invalidAmountInvalidatesTheWholeBody() {
        for (String value : Arrays.asList(null, "null",
                "0.0005",                                   // número JSON: o CT24 manda texto
                "\"-0.01\"", "\"+1\"", "\"1E-18\"", "\"1e3\"", "\"\"", "\".5\"", "\"1.\"", "\"0,5\"", "\" 1\"",
                "\"0.0000000000000000001\"",                // 19 casas
                "\"123456789012345678901\"",                // 21 dígitos inteiros
                "\"NaN\"", "\"Infinity\"")) {
            assertBodyRefused(withItem(itemWith("amountEth", value)));
        }
    }

    @Test
    void invalidTimestampInvalidatesTheWholeBody() {
        for (String value : Arrays.asList(null, "null", "1763266320", "\"2026-11-16\"",
                "\"2026-11-16T04:12:00\"", "\"2026-13-01T00:00:00Z\"", "\"ontem\"")) {
            assertBodyRefused(withItem(itemWith("timestamp", value)));
        }
    }

    @Test
    void invalidStatusInvalidatesTheWholeBody() {
        for (String value : Arrays.asList(null, "null", "1", "\"ok\"", "\"PENDING\"", "\"confirmed\"")) {
            assertBodyRefused(withItem(itemWith("status", value)));
        }
    }

    // ==================== status de erro ====================

    @Test
    void errorStatusesAreUnavailableAndOnlyTheCodeIsLogged() {
        record Case(int status, String body, String expectedLog) {
        }
        String secret = ",\"message\":\"mensagem-secreta-do-corpo\"}";
        String prefix = "WARN CT24 fetchAddressTransactions: HTTP ";
        List<Case> cases = List.of(
                new Case(502, "{\"code\":\"HISTORY_UNAVAILABLE\"" + secret, prefix + "502 HISTORY_UNAVAILABLE"),
                new Case(401, "{\"code\":\"SERVICE_KEY_INVALID\"" + secret,
                        "ERROR CT24 fetchAddressTransactions: HTTP 401 SERVICE_KEY_INVALID;"
                                + " a SERVICE_KEY difere da configurada no External Data Service"),
                new Case(400, "{\"code\":\"ADDRESS_INVALID\"" + secret, prefix + "400 ADDRESS_INVALID"),
                new Case(404, "", prefix + "404 sem code"),
                new Case(404, "<html>mensagem-secreta-do-corpo</html>", prefix + "404 sem code"),
                new Case(429, "{\"code\":\"RATE_LIMIT_EXCEEDED\"" + secret, prefix + "429 RATE_LIMIT_EXCEEDED"),
                new Case(500, "{\"code\":\"INTERNAL_ERROR\"" + secret, prefix + "500 INTERNAL_ERROR"),
                new Case(503, "{\"code\":\"código fora do formato\"" + secret, prefix + "503 sem code"),
                new Case(201, historyBody(ADDRESS, "[]"), prefix + "201 sem code"));

        for (Case c : cases) {
            fake.reply(c.status(), c.body());
            try (LogCapture logs = new LogCapture(AddressHistoryClient.class)) {
                assertThrows(HistoryUnavailableException.class, this::fetch, c.expectedLog());
                assertEquals(c.expectedLog(), logs.all());
                assertLogClean(logs.all());
            }
        }
        assertEquals(cases.size(), fake.requests().size());
    }

    @Test
    void serverErrorIsNotRetried() throws Exception {
        fake.reply(502, "{\"code\":\"HISTORY_UNAVAILABLE\"}");

        assertThrows(HistoryUnavailableException.class, this::fetch);
        Thread.sleep(200); // dá tempo de uma segunda requisição chegar, se houvesse

        assertEquals(1, fake.requests().size());
    }

    // ==================== rede ====================

    @Test
    void slowerThanTheResponseTimeoutFailsInTime() {
        fake.reply(200, historyBody(ADDRESS, "[]")).delay(2_000);
        AddressHistoryClient client = new AddressHistoryClient(fake.baseUrl(), SERVICE_KEY, Duration.ofMillis(300));

        try (LogCapture logs = new LogCapture(AddressHistoryClient.class)) {
            assertTimeoutPreemptively(Duration.ofMillis(1_500),
                    () -> assertThrows(HistoryUnavailableException.class,
                            () -> client.fetchAddressTransactions(ADDRESS)));
            assertEquals("WARN CT24 fetchAddressTransactions: falha de rede (HttpTimeoutException)", logs.all());
            assertLogClean(logs.all());
        }
        assertEquals(1, fake.requests().size());
    }

    @Test
    void connectionRefusedFails() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort(); // ao sair do try, a porta fica fechada
        }
        AddressHistoryClient client = new AddressHistoryClient("http://127.0.0.1:" + closedPort, SERVICE_KEY, TIMEOUT);

        try (LogCapture logs = new LogCapture(AddressHistoryClient.class)) {
            assertThrows(HistoryUnavailableException.class, () -> client.fetchAddressTransactions(ADDRESS));
            assertEquals("WARN CT24 fetchAddressTransactions: falha de rede (ConnectException)", logs.all());
            assertFalse(logs.all().contains(String.valueOf(closedPort)));
        }
    }

    @Test
    void exceptionMessagesCarryNoUrlKeyOrAddress() {
        fake.reply(500, "{\"code\":\"INTERNAL_ERROR\"}");

        HistoryUnavailableException e = assertThrows(HistoryUnavailableException.class, this::fetch);

        assertEquals("CT24 fetchAddressTransactions falhou", e.getMessage());
        assertNull(e.getCause());
    }
}
