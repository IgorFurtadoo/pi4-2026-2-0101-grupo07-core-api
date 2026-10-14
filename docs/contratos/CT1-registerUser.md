# CT1: registerUser

| Linha | Valor |
|---|---|
| Fronteira | HTTP |
| Quem chama | Frontend |
| Quem responde | Core API |
| Atende | E1 e E2 |
| Como chamar | `POST /users` |

Cria o usuário e, na mesma chamada, a carteira custodial associada (CT4 `createWallet`). O usuário só é gravado se a carteira for criada (premissa P6).

## Entrada

| Campo | Tipo | Obrigatório | Exemplo |
|---|---|---|---|
| email (corpo) | texto | sim | ana@example.com |
| password (corpo) | texto | sim | Senha@12345 |

## Saída

Resposta `201`.

| Campo | Tipo | Obrigatório | Exemplo |
|---|---|---|---|
| userId | inteiro | sim | 1 |
| email | texto | sim | ana@example.com |
| ethereumAddress | texto | sim | 0x71C7656EC7ab88b098defB751B7401B5f6d8976F |

## Erros

| Quando | O que volta |
|---|---|
| `email` ausente ou com formato inválido | HTTP 400, `code` EMAIL_INVALID |
| `password` ausente ou com menos de 8 caracteres | HTTP 400, `code` PASSWORD_INVALID |
| Já existe usuário com o mesmo `email` | HTTP 409, `code` EMAIL_ALREADY_REGISTERED |
| Blockchain Service indisponível ou falha ao criar a carteira (o usuário não é gravado) | HTTP 502, `code` WALLET_UNAVAILABLE |
| Falha inesperada, incluindo Banco do Core API indisponível | HTTP 500, `code` INTERNAL_ERROR |
