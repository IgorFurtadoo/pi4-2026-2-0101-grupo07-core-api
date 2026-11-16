package com.lemma.core.service;

import com.lemma.core.client.BlockchainClient;
import com.lemma.core.client.BlockchainRejectedException;
import com.lemma.core.client.BlockchainUnavailableException;
import com.lemma.core.client.TaskState;
import com.lemma.core.dto.SendTransactionRequest;
import com.lemma.core.dto.SendTransactionResponse;
import com.lemma.core.dto.TransactionResponse;
import com.lemma.core.exception.ApiException;
import com.lemma.core.model.EthTransaction;
import com.lemma.core.model.FailureReason;
import com.lemma.core.model.TransactionStatus;
import com.lemma.core.model.User;
import com.lemma.core.repository.EthTransactionRepository;
import com.lemma.core.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Envio de transação (CT9, seção 8.3 do desenho) e acompanhamento (CT13, seção 8.4).
 *
 * Ordem (E4): validar o corpo → ler o usuário (origem = ethereum_address) → CT10 →
 * gravar eth_transaction PENDING → responder. A origem é sempre a carteira do
 * usuário do token, nunca um endereço vindo do cliente.
 *
 * Validação local: o formato do endereço (o checksum EIP-55 é conferido só no
 * Blockchain Service) e a regra do valor (maior que zero, até 18 casas e 20
 * dígitos inteiros, zeros à direita não contam), a mesma da coluna DECIMAL(38,18)
 * e do Blockchain Service, que continua sendo a autoridade: os 400 ADDRESS_INVALID
 * e AMOUNT_INVALID dele são repassados com a mensagem deste módulo.
 *
 * Sem retentativa do CT10 e sem idempotência (limitação da seção 8.3). Log só nas
 * falhas do banco (a classe da exceção e, depois do CT10, o taskId); nada no
 * sucesso. Endereços e valores nunca vão ao log.
 *
 * Acompanhamento: a leitura é sempre por taskId e userId juntos. O estado final é
 * devolvido sem consultar o Blockchain Service; o PENDING consulta o CT14 e, se
 * houver novidade, grava pelo applyTaskState, o mesmo caminho do webhook (CT15).
 */
public class TransactionService {

    private static final Logger log = LoggerFactory.getLogger(TransactionService.class);

    /** Formato do endereço Ethereum: 0x e 40 hexadecimais (cabe no VARCHAR(42)). */
    static final Pattern ADDRESS_FORMAT = Pattern.compile("^0x[0-9a-fA-F]{40}$");

    /** 1 wei = 10^-18 ETH: mais casas não cabem em wei. */
    static final int MAX_DECIMAL_PLACES = 18;

    /** Coluna amount_eth é DECIMAL(38,18): sobram 20 dígitos para a parte inteira. */
    static final int MAX_INTEGER_DIGITS = 20;

    /** taskId: UUID em texto (CHAR(36)). Também protege o caminho do CT14. */
    static final Pattern TASK_ID_FORMAT = Pattern.compile("^[0-9a-fA-F-]{36}$");

    private final UserRepository userRepository;
    private final EthTransactionRepository transactionRepository;
    private final BlockchainClient blockchainClient;
    private final Clock clock;

    public TransactionService(UserRepository userRepository, EthTransactionRepository transactionRepository,
                              BlockchainClient blockchainClient, Clock clock) {
        this.userRepository = userRepository;
        this.transactionRepository = transactionRepository;
        this.blockchainClient = blockchainClient;
        this.clock = clock;
    }

    /** CT9: devolve { taskId, PENDING } depois de o CT10 aceitar e a cópia local ser gravada. */
    public SendTransactionResponse send(long userId, SendTransactionRequest request) {
        // O endereço é conferido primeiro: com os dois inválidos, vale ADDRESS_INVALID.
        String destinationAddress = validDestination(request.destinationAddress());
        BigDecimal amountEth = normalizedAmount(request.amountEth());

        User user = findUser(userId);

        String taskId;
        try {
            taskId = blockchainClient.requestSubmission(user.getEthereumAddress(), destinationAddress, amountEth);
        } catch (BlockchainRejectedException e) {
            // Recusa do Blockchain Service (checksum, valor): mesmo code, mensagem deste módulo.
            throw "ADDRESS_INVALID".equals(e.getCode()) ? addressInvalid() : amountInvalid();
        } catch (BlockchainUnavailableException e) {
            // O motivo já foi ao log pelo cliente. Nada é gravado.
            throw new ApiException(502, "BLOCKCHAIN_UNAVAILABLE",
                    "Não foi possível enviar a transação agora. Tente novamente.");
        }

        // DATETIME(3): o MySQL arredondaria a fração além do milissegundo.
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MILLIS);
        save(new EthTransaction(taskId, userId, destinationAddress, amountEth, now));

        return new SendTransactionResponse(taskId, TransactionStatus.PENDING.name());
    }

    /**
     * CT13: estado da transação do usuário do token.
     *
     * taskId fora do formato, inexistente ou de outro usuário: a mesma resposta
     * 404, sem dizer qual dos casos. O app_user não é lido: um usuário removido
     * não tem transações (chave estrangeira), então recebe o mesmo 404.
     *
     * Estado final: devolvido como está. PENDING: consulta o CT14; se ele falhar,
     * devolve o estado local sem erro (o cliente já logou); se trouxer novidade,
     * grava e relê a linha, e a resposta é sempre o que está gravado, nunca o que
     * o CT14 disse. Sem novidade, nada é gravado (o updatedAt não anda a cada polling).
     */
    public TransactionResponse get(long userId, String taskId) {
        if (taskId == null || !TASK_ID_FORMAT.matcher(taskId).matches()) {
            throw transactionNotFound();
        }
        // O MySQL compara CHAR(36) sem diferença de caixa; aqui vale o mesmo para todos.
        String normalizedTaskId = taskId.toLowerCase(Locale.ROOT);

        EthTransaction local = findOwned(normalizedTaskId, userId);
        if (local.getStatus() != TransactionStatus.PENDING) {
            return toResponse(local);
        }

        TaskState remote;
        try {
            remote = blockchainClient.getTask(normalizedTaskId);
        } catch (BlockchainUnavailableException e) {
            // Falha tolerada (CT13): o motivo já foi ao log pelo cliente.
            return toResponse(local);
        }
        if (sameState(local, remote.status(), remote.transactionHash(), remote.failureReason())) {
            return toResponse(local);
        }

        applyTaskState(normalizedTaskId, remote.status(), remote.transactionHash(), remote.failureReason());
        return toResponse(findOwned(normalizedTaskId, userId));
    }

    /**
     * Regra de convergência (seção 8.4 do desenho), usada pela consulta ativa
     * (CT13) e pelo webhook (CT15): uma atualização condicional que só altera a
     * linha ainda PENDING. updated_at é o relógio do Core API no momento da mudança.
     *
     * @return true se a linha mudou; false se ela já estava em estado final (ou não existe)
     */
    public boolean applyTaskState(String taskId, TransactionStatus status, String transactionHash,
                                  FailureReason failureReason) {
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MILLIS);
        try {
            return transactionRepository.updateIfPending(taskId, status, transactionHash, failureReason, now) > 0;
        } catch (RuntimeException e) {
            log.warn("Atualização da transação: falha no banco ({})", e.getClass().getSimpleName());
            throw internalError();
        }
    }

    /** O estado gravado é igual ao informado? Status, hash (exato) e motivo. */
    private static boolean sameState(EthTransaction row, TransactionStatus status, String transactionHash,
                                     FailureReason failureReason) {
        return row.getStatus() == status
                && Objects.equals(row.getTransactionHash(), transactionHash)
                && row.getFailureReason() == failureReason;
    }

    /** Linha do taskId que pertence ao usuário; senão 404. Falha do banco: 500, só a classe no log. */
    private EthTransaction findOwned(String taskId, long userId) {
        Optional<EthTransaction> transaction;
        try {
            transaction = transactionRepository.findByTaskIdAndUserId(taskId, userId);
        } catch (RuntimeException e) {
            log.warn("Acompanhamento: falha ao consultar o banco ({})", e.getClass().getSimpleName());
            throw internalError();
        }
        return transaction.orElseThrow(TransactionService::transactionNotFound);
    }

    /**
     * A entidade nunca sai pelo JSON. amountEth sem zeros à direita (o banco
     * devolve escala 18) e sem expoente; updatedAt em segundos, ISO 8601 UTC.
     */
    private static TransactionResponse toResponse(EthTransaction transaction) {
        BigDecimal amount = transaction.getAmountEth().stripTrailingZeros();
        if (amount.scale() < 0) {
            amount = amount.setScale(0);
        }
        return new TransactionResponse(
                transaction.getTaskId(),
                transaction.getStatus().name(),
                transaction.getDestinationAddress(),
                amount,
                transaction.getTransactionHash(),
                transaction.getFailureReason() == null ? null : transaction.getFailureReason().name(),
                transaction.getUpdatedAt().truncatedTo(ChronoUnit.SECONDS).toString());
    }

    /** Destino obrigatório no formato 0x + 40 hexadecimais; volta como o usuário enviou. */
    private static String validDestination(String destinationAddress) {
        if (destinationAddress == null || !ADDRESS_FORMAT.matcher(destinationAddress).matches()) {
            throw addressInvalid();
        }
        return destinationAddress;
    }

    /**
     * Valor maior que zero, com até 18 casas e até 20 dígitos inteiros; zeros à
     * direita não contam (0.0100 é 0.01; 1E+2 é 100). Devolve o valor sem zeros à
     * direita e com escala de 0 a 18, que segue para o CT10 e para o banco.
     *
     * Nunca chama toPlainString: 1E+999999999 é um BigDecimal válido que viraria um
     * texto de 1 GB. As contas usam só precision e scale, nesta ordem:
     * - dígitos inteiros = precision - scale, em long (em int, 1E+2147483647 estoura
     *   e passaria). A conta não muda com zeros à direita, então é feita antes do
     *   stripTrailingZeros, que lança ArithmeticException em valores como
     *   100E+2147483647;
     * - com até 20 dígitos inteiros, a escala é de no mínimo -20 e o
     *   stripTrailingZeros e o setScale(0) são seguros.
     */
    private static BigDecimal normalizedAmount(BigDecimal amountEth) {
        if (amountEth == null || amountEth.signum() <= 0) {
            throw amountInvalid();
        }
        long integerDigits = (long) amountEth.precision() - amountEth.scale();
        if (integerDigits > MAX_INTEGER_DIGITS) {
            throw amountInvalid();
        }
        BigDecimal stripped = amountEth.stripTrailingZeros();
        if (stripped.scale() > MAX_DECIMAL_PLACES) {
            throw amountInvalid();
        }
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }

    /** A mensagem do banco pode trazer dados do usuário: vai ao log só a classe da exceção. */
    private User findUser(long userId) {
        Optional<User> user;
        try {
            user = userRepository.findById(userId);
        } catch (RuntimeException e) {
            log.warn("Envio: falha ao consultar o banco ({})", e.getClass().getSimpleName());
            throw internalError();
        }
        return user.orElseThrow(ApiException::tokenInvalid);
    }

    /**
     * Grava a cópia local. Se falhar, a task criada no CT10 segue no Blockchain
     * Service sem registro aqui (seção 8.3): o taskId vai ao log para ser
     * rastreável, com a classe da exceção e nunca a mensagem dela.
     */
    private void save(EthTransaction transaction) {
        try {
            transactionRepository.save(transaction);
        } catch (RuntimeException e) {
            log.warn("Task órfã {}: falha ao gravar a transação ({})",
                    transaction.getTaskId(), e.getClass().getSimpleName());
            throw internalError();
        }
    }

    private static ApiException addressInvalid() {
        return new ApiException(400, "ADDRESS_INVALID", "Informe um endereço Ethereum válido.");
    }

    private static ApiException amountInvalid() {
        return new ApiException(400, "AMOUNT_INVALID",
                "Informe um valor maior que zero, com no máximo 18 casas decimais e 20 dígitos inteiros.");
    }

    /** Inexistente, de outro usuário ou taskId malformado: a mesma resposta. */
    private static ApiException transactionNotFound() {
        return new ApiException(404, "TRANSACTION_NOT_FOUND", "Transação não encontrada.");
    }

    private static ApiException internalError() {
        return new ApiException(500, "INTERNAL_ERROR", "Erro interno.");
    }
}
