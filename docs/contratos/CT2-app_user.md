# CT2: app_user

| Linha | Valor |
|---|---|
| Fronteira | Banco |
| Quem chama | Core API |
| Quem responde | Banco do Core API |
| Atende | E1, E2, E3, E4 e E8 |
| Como chamar | Tabela `app_user`, chave `id`. Busca alternativa por `email` (único). Operações usadas: gravar, ler por `id`, ler por `email`. |

O nome `app_user` evita a palavra reservada `user` (premissa P9).

## Entrada

Campos para gravar um usuário (nomes de coluna em `snake_case`, conforme a convenção da visão geral).

| Campo | Tipo | Obrigatório | Exemplo |
|---|---|---|---|
| id | inteiro | não (gerado pelo banco) | 1 |
| email | texto | sim | ana@example.com |
| password_hash | texto | sim | $2a$12$R9h/cIPz0gi.URNNX3kh2OPST9/PgBkqquzi.Ss7KIUgO2t0jWMUW |
| ethereum_address | texto | sim | 0x71C7656EC7ab88b098defB751B7401B5f6d8976F |
| created_at | data e hora | sim | 2026-10-20T14:30:00Z |

Para ler, a entrada é `id` (inteiro, sim, exemplo 1) ou `email` (texto, sim, exemplo ana@example.com).

## Saída

Campos devolvidos na leitura de um usuário.

| Campo | Tipo | Obrigatório | Exemplo |
|---|---|---|---|
| id | inteiro | sim | 1 |
| email | texto | sim | ana@example.com |
| password_hash | texto | sim | $2a$12$R9h/cIPz0gi.URNNX3kh2OPST9/PgBkqquzi.Ss7KIUgO2t0jWMUW |
| ethereum_address | texto | sim | 0x71C7656EC7ab88b098defB751B7401B5f6d8976F |
| created_at | data e hora | sim | 2026-10-20T14:30:00Z |

## Erros

| Quando | O que volta |
|---|---|
| Gravar com `email` já existente (restrição de unicidade) | Violação de unicidade. O Core API converte em HTTP 409, `code` EMAIL_ALREADY_REGISTERED |
| Ler por `id` ou `email` inexistente | Nenhum registro. O Core API trata como usuário não encontrado (no login vira HTTP 401, `code` INVALID_CREDENTIALS) |
| Gravar sem um campo obrigatório | Violação de campo obrigatório. O Core API responde HTTP 500, `code` INTERNAL_ERROR |
| Banco do Core API indisponível | Falha de conexão. O Core API responde HTTP 500, `code` INTERNAL_ERROR |
