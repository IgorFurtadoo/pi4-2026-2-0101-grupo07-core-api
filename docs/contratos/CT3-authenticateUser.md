# CT3: authenticateUser

| Linha | Valor |
|---|---|
| Fronteira | HTTP |
| Quem chama | Frontend |
| Quem responde | Core API |
| Atende | E1 |
| Como chamar | `POST /auth/login` |

Emite um token JWT assinado pelo Core API (premissa P1). O mesmo erro `INVALID_CREDENTIALS` vale para e-mail inexistente e senha incorreta, para não revelar quais e-mails existem.

## Entrada

| Campo | Tipo | Obrigatório | Exemplo |
|---|---|---|---|
| email (corpo) | texto | sim | ana@example.com |
| password (corpo) | texto | sim | Senha@12345 |

## Saída

Resposta `200`.

| Campo | Tipo | Obrigatório | Exemplo |
|---|---|---|---|
| token | texto | sim | eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.Kq8vN2xT5mYpR7wZ1cLd3hJfA9sUeB4oGiVtXyW6nMk |
| expiresAt | data e hora | sim | 2026-10-20T18:30:00Z |

## Erros

| Quando | O que volta |
|---|---|
| `email` ou `password` ausentes | HTTP 400, `code` REQUEST_INVALID |
| E-mail inexistente ou senha incorreta | HTTP 401, `code` INVALID_CREDENTIALS, `message` "E-mail ou senha incorretos". O Frontend exibe a mensagem e não libera o acesso |
| Falha inesperada, incluindo Banco do Core API indisponível | HTTP 500, `code` INTERNAL_ERROR |

Nota (emenda do H03.1, decidida pelo responsável do módulo (Igor)):
- **E-mail:** antes da busca, o e-mail é normalizado (espaços nas pontas removidos e letras em minúsculas), como no CT1. Quem se cadastrou como `Ana@Example.com` entra com `ana@example.com` ou ` ANA@example.com `.
- **Campos vazios:** `email` vazio ou só com espaços, ou `password` vazio, respondem 400 REQUEST_INVALID, como os ausentes. Senha só com espaços não é vazia: é conferida normalmente.
- **expiresAt:** é o instante `exp` do token, em ISO 8601 UTC, sem fração de segundo (por exemplo `2026-10-20T18:30:00Z`).
- **Corpo inválido:** corpo ausente, JSON malformado, campo com tipo errado (por exemplo `email` numérico) ou o JSON literal `null` respondem 400 REQUEST_INVALID, como no CT1.
