# Lemma

Aplicação web de carteira de criptoativos custodial para a rede Ethereum Sepolia Testnet, que permi>

## Integrantes

- Igor de Toledo Furtado
- Lucas Athanasio Bueno de Andrade
- Guillermo Perdomo Sevilla Neto
- Nicollas Fabbio

## Documentação de fluxos e contratos

Os fluxos do sistema (visão geral e E1–E9) estão no repositório do frontend: https://github.com/Igo>

Os contratos deste módulo estão em docs/contratos/.

## Como abastecer a carteira de um usuário (Sepolia)

A carteira de cada usuário é criada vazia pelo Blockchain Service no cadastro. Para ter saldo, ela recebe ETH da **carteira de demonstração**: uma carteira custodial do Blockchain Service abastecida antes na torneira PoW da Sepolia. O `scripts/e2e-demo.sh` faz isso no passo 3, com um pedido de envio (CT10) direto ao Blockchain Service, origem na carteira de demonstração e destino no endereço do usuário.

- Informe o endereço público da carteira de demonstração em `DEMO_WALLET_ADDRESS`. O roteiro para, sem enviar nada, se ela tiver menos de 0.01 ETH.
- Valor: `FUND_ETH`, padrão 0.003, no máximo 0.005.
- Para enviar, a carteira do usuário precisa de **valor + 500.000 × preço do gás**, porque o Blockchain Service usa limite de gás fixo de 500.000. Com menos, a rede recusa e a transação termina `FAILED`/`REJECTED_BY_NETWORK`.
- Só testnet: nunca mainnet nem valores reais.

## Roteiros contra o Blockchain Service real

Pré-requisitos: `core-api` e `core-db` no ar (`docker compose up -d`) e o Blockchain Service no ar na rede `lemma-dev`, com a mesma `SERVICE_KEY`. Os roteiros rodam no host e usam só `bash`, `curl`, `grep`, `sed`, `awk` e `docker`. Segredos não aparecem: a chave de serviço só é usada dentro do container `core-api`, a senha do banco só dentro do `core-db`, e a senha e o token do usuário de teste não são impressos. Não rode com `bash -x`. Os dois criam usuários `c9-teste-<aleatório>@example.com`, que ficam no banco.

| Roteiro | O que prova | Custo | Tempo |
|---|---|---|---|
| `scripts/concurrency-update-if-pending.sh` | O `UPDATE` condicional sob concorrência: notificações contraditórias do webhook e `GET` em paralelo sobre linhas `PENDING` sintéticas | Nenhum ETH | ~30 s |
| `DEMO_WALLET_ADDRESS=0x... scripts/e2e-demo.sh` | Cadastro, login, saldo, abastecimento, recuperação pela consulta ativa, envio com `CONFIRMED` gravado pelo webhook e envio com `GET` em paralelo | 0.003 ETH da demonstração para o usuário e 2 envios de 0.0005 de volta; gás de ~250 mil unidades no total | 2 a 5 min |

Variáveis do `e2e-demo.sh`: `DEMO_WALLET_ADDRESS` (obrigatória), `CORE_API_URL` (padrão `http://127.0.0.1:8080`), `FUND_ETH` (0.003, no máximo 0.005), `SEND_ETH` (0.0005, no máximo 0.0005), `CONFIRM_TIMEOUT_SECONDS` (180), `RACE_LOOPS` (8), `RACE_INTERVAL` (0.1). Do `concurrency-update-if-pending.sh`: `ROWS` (10) e `ROUNDS` (5). Os dois aceitam `CORE_API_CONTAINER` e `CORE_DB_CONTAINER`; o `e2e-demo.sh` também `BLOCKCHAIN_CONTAINER`. Cada passo é impresso, e qualquer falha encerra com código 1. Resultados em `docs/desenho-core-api.md`, seções 11.1 e 11.2.

## Histórico da carteira

`GET /wallet/transactions` (ficha `docs/contratos/CT26-getWalletTransactions.md`) devolve as até 20 transações mais recentes da carteira do usuário do token, lidas do Blockscout público pelo External Data Service (CT24). O endereço vem sempre do usuário do token. Só aparecem transações já mineradas; um envio `PENDING` continua em `GET /transactions/{taskId}`.

- Pré-requisito: o External Data Service no ar na rede `lemma-dev`, com a mesma `SERVICE_KEY`, em `EXTERNAL_DATA_SERVICE_URL`. Sem ele, a rota responde 502 `HISTORY_UNAVAILABLE` e o resto do Core API segue funcionando.
- Sem rate limiting no Core API: o External Data Service guarda o histórico por endereço durante 12 s.
- Prova contra o External Data Service real: `docs/desenho-core-api.md`, seção 11.3.
