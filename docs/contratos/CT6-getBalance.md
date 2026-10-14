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
