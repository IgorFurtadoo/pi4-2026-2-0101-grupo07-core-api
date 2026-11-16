# CT6: getBalance

| Linha | Valor |
|---|---|
| Fronteira | HTTP |
| Quem chama | Frontend |
| Quem responde | Core API |
| Atende | E3 |
| Como chamar | `GET /wallet/balance` |

O Core API identifica o usuário pelo token e usa o `ethereum_address` guardado em CT2 `app_user`. O Frontend não informa endereço.

## Entrada

| Campo | Tipo | Obrigatório | Exemplo |
|---|---|---|---|
| Authorization (cabeçalho) | texto | sim | Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.Kq8vN2xT5mYpR7wZ1cLd3hJfA9sUeB4oGiVtXyW6nMk |

## Saída

Resposta `200`.

| Campo | Tipo | Obrigatório | Exemplo |
|---|---|---|---|
| ethereumAddress | texto | sim | 0x71C7656EC7ab88b098defB751B7401B5f6d8976F |
| balanceEth | decimal | sim | 0.25 |
| network | um de: SEPOLIA | sim | SEPOLIA |

## Erros

| Quando | O que volta |
|---|---|
| Token ausente, inválido ou expirado | HTTP 401, `code` TOKEN_INVALID |
| Blockchain Service ou Provedor RPC indisponível ou com falha | HTTP 502, `code` BLOCKCHAIN_UNAVAILABLE |
| Falha inesperada, incluindo Banco do Core API indisponível | HTTP 500, `code` INTERNAL_ERROR |

Nota (emenda do H04.2, decidida pelo responsável do módulo (Igor)):
- **Usuário removido:** se o usuário do token não existir mais em CT2 `app_user`, a resposta é HTTP 401, `code` TOKEN_INVALID, igual à do token inválido. O CT7 não é chamado.
- **Resposta inesperada do Blockchain Service:** qualquer resposta do CT7 que não seja a esperada (tempo esgotado, conexão recusada, qualquer status diferente de 200, inclusive 400, 401 e 404, ou corpo fora do formato, como `balanceEth` ausente, em texto, negativo ou com mais de 18 casas, ou `network` diferente de SEPOLIA) responde HTTP 502, `code` BLOCKCHAIN_UNAVAILABLE.
- **balanceEth:** é um número JSON (não texto) em notação plana, sem expoente, com o valor exato devolvido pelo CT7 (por exemplo `0.000000000000000001`, nunca `1E-18`).
- **ethereumAddress:** é o guardado em CT2 `app_user`, o mesmo enviado ao CT7.
