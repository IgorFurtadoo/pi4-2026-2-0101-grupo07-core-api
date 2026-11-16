package com.lemma.core.service;

import com.lemma.core.client.BalanceResult;
import com.lemma.core.client.BlockchainClient;
import com.lemma.core.client.BlockchainRejectedException;
import com.lemma.core.client.BlockchainUnavailableException;
import com.lemma.core.client.TaskState;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Blockchain Service falso para os testes: devolve endereços em sequência,
 * saldos configurados por endereço (zero se não configurado), taskIds em
 * sequência, estados de task configurados por taskId, ou falha.
 */
public class FakeBlockchainClient implements BlockchainClient {

    /** Faz o CT4 falhar. */
    public boolean fail;
    public int createWalletCalls;

    /** Saldo por endereço no CT7; endereço ausente tem saldo zero, como uma carteira nova. */
    public final Map<String, BigDecimal> balances = new HashMap<>();
    /** Faz o CT7 falhar (separado do CT4: o cadastro funciona e o saldo falha). */
    public boolean failGetBalance;
    /** Endereços consultados no CT7, na ordem. */
    public final List<String> balanceQueries = new ArrayList<>();

    /** Faz o CT10 falhar (indisponível). */
    public boolean failRequestSubmission;
    /** Quando não nulo, o CT10 recusa com este code (ADDRESS_INVALID ou AMOUNT_INVALID). */
    public String rejectRequestSubmissionWith;
    /** Pedidos recebidos no CT10, na ordem (inclusive os que falharam). */
    public final List<Submission> submissions = new ArrayList<>();

    /** Estado de cada task no CT14; taskId ausente falha, como o 404 TASK_NOT_FOUND. */
    public final Map<String, TaskState> tasks = new HashMap<>();
    /** Faz o CT14 falhar (indisponível). */
    public boolean failGetTask;
    /** taskIds consultados no CT14, na ordem. */
    public final List<String> taskQueries = new ArrayList<>();
    /**
     * Quando não nulo, roda dentro do CT14, antes de responder: simula outro
     * caminho (o webhook) atualizando a linha enquanto a consulta está em andamento.
     */
    public Runnable onGetTask;

    /** Um pedido do CT10 como chegou ao falso. */
    public record Submission(String sourceAddress, String destinationAddress, BigDecimal amountEth) {
    }

    @Override
    public String createWallet() {
        createWalletCalls++;
        if (fail) {
            throw new BlockchainUnavailableException("CT4 createWallet");
        }
        return address(createWalletCalls);
    }

    @Override
    public BalanceResult getBalance(String ethereumAddress) {
        balanceQueries.add(ethereumAddress);
        if (failGetBalance) {
            throw new BlockchainUnavailableException("CT7 getWalletBalance");
        }
        return new BalanceResult(balances.getOrDefault(ethereumAddress, BigDecimal.ZERO), "SEPOLIA");
    }

    @Override
    public String requestSubmission(String sourceAddress, String destinationAddress, BigDecimal amountEth) {
        submissions.add(new Submission(sourceAddress, destinationAddress, amountEth));
        if (failRequestSubmission) {
            throw new BlockchainUnavailableException("CT10 requestSubmission");
        }
        if (rejectRequestSubmissionWith != null) {
            throw new BlockchainRejectedException(rejectRequestSubmissionWith);
        }
        return taskId(submissions.size());
    }

    @Override
    public TaskState getTask(String taskId) {
        taskQueries.add(taskId);
        if (onGetTask != null) {
            onGetTask.run();
        }
        TaskState state = tasks.get(taskId);
        if (failGetTask || state == null) {
            throw new BlockchainUnavailableException("CT14 getTask");
        }
        return state;
    }

    /** taskId previsível, no formato de UUID: termina no número do pedido. */
    public static String taskId(int n) {
        return String.format("00000000-0000-0000-0000-%012d", n);
    }

    /** Endereço previsível: 0x seguido de 40 hexadecimais terminados no número da chamada. */
    public static String address(int n) {
        return "0x" + String.format("%040x", n);
    }
}
