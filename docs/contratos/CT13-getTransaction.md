# CT13: getTransaction

| Linha | Valor |
|---|---|
| Fronteira | HTTP |
| Quem chama | Frontend |
| Quem responde | Core API |
| Atende | E5 e E9 |
| Como chamar | `GET /transactions/{taskId}` |

O Frontend chama este contrato em polling até o `status` ser `CONFIRMED` ou `FAILED` (premissa P4). Enquanto o estado local for `PENDING`, o Core API consulta o Blockchain Service (CT14) antes de responder. Se essa consulta falhar, o Core API devolve o último estado local conhecido, sem erro.

## Entrada

| Campo | Tipo | Obrigatório | Exemplo |
|---|---|---|---|
| Authorization (cabeçalho) | texto | sim | Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.Kq8vN2xT5mYpR7wZ1cLd3hJfA9sUeB4oGiVtXyW6nMk |
| taskId (caminho) | texto | sim | a3f1c2d4-5b6e-4f70-8a91-0c2d3e4f5a6b |

## Saída

Resposta `200`.

| Campo | Tipo | Obrigatório | Exemplo |
|---|---|---|---|
| taskId | texto | sim | a3f1c2d4-5b6e-4f70-8a91-0c2d3e4f5a6b |
| status | um de: PENDING, CONFIRMED, FAILED | sim | CONFIRMED |
| destinationAddress | texto | sim | 0x5B38Da6a701c568545dCfcB03FcB875f56beddC4 |
| amountEth | decimal | sim | 0.01 |
| transactionHash | texto | não (ausente antes de o Blockchain Service informar) | 0x5c504ed432cb51138bcf09aa5e8a410dd4a1e204ef84bfed1be16dfba1b22060 |
| failureReason | um de: REJECTED_BY_NETWORK, TRANSACTION_REVERTED, INTERNAL_ERROR | não (só quando `status` é FAILED) | REJECTED_BY_NETWORK |
| updatedAt | data e hora | sim | 2026-10-20T14:36:10Z |

## Erros

| Quando | O que volta |
|---|---|
| Token ausente, inválido ou expirado | HTTP 401, `code` TOKEN_INVALID |
| `taskId` inexistente ou pertencente a outro usuário | HTTP 404, `code` TRANSACTION_NOT_FOUND |
| Falha inesperada, incluindo Banco do Core API indisponível | HTTP 500, `code` INTERNAL_ERROR |

Nota (emenda do H08.2, decidida pelo responsável do módulo (Igor)):
- **Leitura por `taskId` e usuário:** a transação é lida por `taskId` e pelo usuário do token, juntos. `taskId` inexistente, de outro usuário ou fora do formato de UUID (36 caracteres, hexadecimais e hífens) recebem a mesma resposta: HTTP 404, `code` TRANSACTION_NOT_FOUND. O `taskId` é comparado sem diferença de maiúsculas e minúsculas.
- **Usuário removido:** o Core API não consulta CT2 `app_user` nesta rota. Um token válido de usuário sem transações (inclusive um usuário que não existe mais) recebe o mesmo 404.
- **Estado final:** `CONFIRMED` ou `FAILED` é devolvido como está gravado, sem consultar o Blockchain Service.
- **`PENDING`:** o Core API consulta o CT14 `getTask` com tempo limite de 5 s. Se a consulta falhar (tempo esgotado, rede, 404 TASK_NOT_FOUND, 5xx ou resposta fora do formato), devolve o estado local, com 200 e sem erro. Se o CT14 trouxer novidade (status, hash ou motivo diferentes do gravado), grava pela atualização condicional do CT12 e responde com a linha gravada, nunca com o que o CT14 disse. Sem novidade, nada é gravado e `updatedAt` não muda. Uma resposta do CT14 com `CONFIRMED` sem `transactionHash` é tratada como fora do formato.
- **Formato:** `transactionHash` e `failureReason` saem ausentes do JSON quando não existem (nunca `null`). `amountEth` sai sem zeros à direita e sem expoente (`0.01`, `100`, `0.000000000000000001`). `updatedAt` é o `updated_at` da linha, em ISO 8601 UTC truncado em segundos (ex.: `2026-10-20T14:36:10Z`).
