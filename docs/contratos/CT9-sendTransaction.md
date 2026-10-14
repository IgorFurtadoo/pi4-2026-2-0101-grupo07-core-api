# CT9: sendTransaction

| Linha | Valor |
|---|---|
| Fronteira | HTTP |
| Quem chama | Frontend |
| Quem responde | Core API |
| Atende | E4 e E8 |
| Como chamar | `POST /transactions` |

A resposta confirma que a solicitação foi **aceita**, não que a transação foi confirmada na rede. O Frontend acompanha o resultado com CT13 `getTransaction`.

## Entrada

| Campo | Tipo | Obrigatório | Exemplo |
|---|---|---|---|
| Authorization (cabeçalho) | texto | sim | Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.Kq8vN2xT5mYpR7wZ1cLd3hJfA9sUeB4oGiVtXyW6nMk |
| destinationAddress (corpo) | texto | sim | 0x5B38Da6a701c568545dCfcB03FcB875f56beddC4 |
| amountEth (corpo) | decimal | sim | 0.01 |

## Saída

Resposta `202`.

| Campo | Tipo | Obrigatório | Exemplo |
|---|---|---|---|
| taskId | texto | sim | a3f1c2d4-5b6e-4f70-8a91-0c2d3e4f5a6b |
| status | um de: PENDING | sim | PENDING |

## Erros

| Quando | O que volta |
|---|---|
| Token ausente, inválido ou expirado | HTTP 401, `code` TOKEN_INVALID |
| `destinationAddress` ausente ou com formato inválido | HTTP 400, `code` ADDRESS_INVALID |
| `amountEth` ausente, zero ou negativo | HTTP 400, `code` AMOUNT_INVALID |
| Blockchain Service indisponível ou recusou a solicitação | HTTP 502, `code` BLOCKCHAIN_UNAVAILABLE |
| Falha inesperada, incluindo Banco do Core API indisponível | HTTP 500, `code` INTERNAL_ERROR |
