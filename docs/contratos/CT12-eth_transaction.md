# CT12: eth_transaction

| Linha | Valor |
|---|---|
| Fronteira | Banco |
| Quem chama | Core API |
| Quem responde | Banco do Core API |
| Atende | E4, E5, E8 e E9 |
| Como chamar | Tabela `eth_transaction`, chave `task_id`. Operações usadas: gravar, atualizar e ler por `task_id`. |

Cópia local do estado de cada transação, para o Frontend fazer polling no Core API. É atualizada pelo webhook (CT15) ou pela consulta ativa ao Blockchain Service (CT14). Os estados seguem a premissa P3.

## Entrada

Campos para gravar ou atualizar uma transação (nomes de coluna em `snake_case`).

| Campo | Tipo | Obrigatório | Exemplo |
|---|---|---|---|
| task_id | texto | sim | a3f1c2d4-5b6e-4f70-8a91-0c2d3e4f5a6b |
| user_id | inteiro | sim | 1 |
| destination_address | texto | sim | 0x5B38Da6a701c568545dCfcB03FcB875f56beddC4 |
| amount_eth | decimal | sim | 0.01 |
| status | um de: PENDING, CONFIRMED, FAILED | sim | PENDING |
| transaction_hash | texto | não (preenchido quando o Blockchain Service informa) | 0x5c504ed432cb51138bcf09aa5e8a410dd4a1e204ef84bfed1be16dfba1b22060 |
| failure_reason | um de: REJECTED_BY_NETWORK, TRANSACTION_REVERTED, INTERNAL_ERROR | não (só quando `status` é FAILED) | REJECTED_BY_NETWORK |
| created_at | data e hora | sim | 2026-10-20T14:35:00Z |
| updated_at | data e hora | sim | 2026-10-20T14:35:00Z |

## Saída

Campos devolvidos na leitura de uma transação.

| Campo | Tipo | Obrigatório | Exemplo |
|---|---|---|---|
| task_id | texto | sim | a3f1c2d4-5b6e-4f70-8a91-0c2d3e4f5a6b |
| user_id | inteiro | sim | 1 |
| destination_address | texto | sim | 0x5B38Da6a701c568545dCfcB03FcB875f56beddC4 |
| amount_eth | decimal | sim | 0.01 |
| status | um de: PENDING, CONFIRMED, FAILED | sim | CONFIRMED |
| transaction_hash | texto | não (ausente antes de o Blockchain Service informar) | 0x5c504ed432cb51138bcf09aa5e8a410dd4a1e204ef84bfed1be16dfba1b22060 |
| failure_reason | um de: REJECTED_BY_NETWORK, TRANSACTION_REVERTED, INTERNAL_ERROR | não (só quando `status` é FAILED) | REJECTED_BY_NETWORK |
| created_at | data e hora | sim | 2026-10-20T14:35:00Z |
| updated_at | data e hora | sim | 2026-10-20T14:36:10Z |

## Erros

| Quando | O que volta |
|---|---|
| Gravar com `task_id` já existente | Violação de unicidade da chave. O Core API responde HTTP 500, `code` INTERNAL_ERROR |
| Ler ou atualizar um `task_id` inexistente | Nenhum registro. Em CT13 e CT15 vira HTTP 404, `code` TRANSACTION_NOT_FOUND |
| Banco do Core API indisponível | Falha de conexão. O Core API responde HTTP 500, `code` INTERNAL_ERROR |
