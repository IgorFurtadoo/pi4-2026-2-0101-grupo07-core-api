# CT26: getWalletTransactions

| Linha | Valor |
|---|---|
| Fronteira | HTTP |
| Quem chama | Frontend |
| Quem responde | Core API |
| Atende | Histórico da carteira (`desenho-frontend.md`, seção 7, opção D; fora da Especificação) |
| Como chamar | `GET /wallet/transactions` |

Devolve as transações mais recentes da carteira do usuário, lidas da blockchain pelo External Data Service (CT24). O Core API identifica o usuário pelo token e usa o `ethereum_address` guardado em CT2 `app_user`. O Frontend não informa endereço. Só aparecem transações já mineradas: um envio ainda `PENDING` continua no acompanhamento (CT13).

## Entrada

| Campo | Tipo | Obrigatório | Exemplo |
|---|---|---|---|
| Authorization (cabeçalho) | texto | sim | Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.Kq8vN2xT5mYpR7wZ1cLd3hJfA9sUeB4oGiVtXyW6nMk |

Parâmetros de consulta são ignorados: o endereço nunca vem da entrada.

## Saída

Resposta `200`.

| Campo | Tipo | Obrigatório | Exemplo |
|---|---|---|---|
| ethereumAddress | texto | sim | 0x71C7656EC7ab88b098defB751B7401B5f6d8976F |
| network | um de: SEPOLIA | sim | SEPOLIA |
| transactions | lista de (objeto (WalletTransaction)) | sim | até 20 itens, mais recentes primeiro; lista vazia se a carteira não tiver transações |

Campos de `objeto (WalletTransaction)`:

| Campo | Tipo | Obrigatório | Exemplo |
|---|---|---|---|
| hash | texto | sim | 0x336be534b01ce174e559209ffdde79b15ffeb5f47a66b16f77737d45fcef7598 |
| direction | um de: SENT, RECEIVED, SELF | sim | RECEIVED |
| counterpartyAddress | texto ou nulo | sim | 0x6bdB5129e34773c822044a2078e6ba1576A258E6 |
| amountEth | decimal | sim | 0.0005 |
| timestamp | data e hora | sim | 2026-11-16T04:12:00Z |
| status | um de: CONFIRMED, FAILED | sim | CONFIRMED |

Os itens são os do CT24, na mesma ordem e com o mesmo significado: `direction` do ponto de vista da carteira do usuário; `counterpartyAddress` é a outra ponta, nulo só na criação de contrato que falhou; `status` FAILED é transação minerada que reverteu.

Exemplo:

```json
{
  "ethereumAddress": "0x71C7656EC7ab88b098defB751B7401B5f6d8976F",
  "network": "SEPOLIA",
  "transactions": [
    {
      "hash": "0x336be534b01ce174e559209ffdde79b15ffeb5f47a66b16f77737d45fcef7598",
      "direction": "RECEIVED",
      "counterpartyAddress": "0x6bdB5129e34773c822044a2078e6ba1576A258E6",
      "amountEth": 0.0005,
      "timestamp": "2026-11-16T04:12:00Z",
      "status": "CONFIRMED"
    }
  ]
}
```

## Erros

| Quando | O que volta |
|---|---|
| Token ausente, inválido ou expirado | HTTP 401, `code` TOKEN_INVALID |
| External Data Service ou Blockscout indisponível ou com falha | HTTP 502, `code` HISTORY_UNAVAILABLE |
| Falha inesperada, incluindo Banco do Core API indisponível | HTTP 500, `code` INTERNAL_ERROR |

Nota (H08.4, decidida pelo responsável do módulo):
- **Usuário removido:** se o usuário do token não existir mais em CT2 `app_user`, a resposta é HTTP 401, `code` TOKEN_INVALID, igual à do token inválido. O CT24 não é chamado.
- **ethereumAddress:** é o guardado em CT2 `app_user`, o mesmo enviado ao CT24.
- **Resposta do External Data Service (CT24):** uma única tentativa, com o mesmo tempo limite do CT18 (conexão de 3 s, resposta de `EXTERNAL_DATA_TIMEOUT_SECONDS`) e a mesma `X-Service-Key`. Qualquer resposta que não seja 200 com o corpo no formato (tempo esgotado, conexão recusada, qualquer outro status, inclusive 400 ADDRESS_INVALID, 401 SERVICE_KEY_INVALID e o 502 HISTORY_UNAVAILABLE do próprio CT24, ou corpo ilegível ou fora do formato) responde HTTP 502, `code` HISTORY_UNAVAILABLE, mensagem "Não foi possível obter o histórico agora. Tente novamente.". Nunca resposta parcial: um item inválido descarta a lista toda.
- **Corpo do CT24 conferido:** `ethereumAddress` igual ao enviado sem diferenciar maiúsculas; `network` exatamente SEPOLIA; `transactions` lista de no máximo 20 itens; em cada item, `hash` `^0x[0-9a-fA-F]{64}$`, `direction` e `status` nos valores da ficha, `counterpartyAddress` presente (nulo ou `^0x[0-9a-fA-F]{40}$`), `amountEth` **texto** em notação plana com até 20 dígitos inteiros e até 18 casas, `timestamp` aceito por `Instant.parse`. Campos extras são ignorados.
- **amountEth:** é um número JSON (não texto) em notação plana, sem expoente, com o valor exato recebido do CT24 (por exemplo `0.000000000000000001`, nunca `1E-18`). Mesmo tipo do `balanceEth` do CT6 e do `amountEth` do CT13: o mesmo nome de campo não muda de tipo entre rotas do Core API, e o Frontend já lê esses campos pelo texto exato do número (reviver do `JSON.parse`). Limitação: um navegador sem acesso ao texto do número no reviver vê o valor arredondado, como no saldo. O CT24 manda texto; o Core API converte por `BigDecimal`, sem `double`.
- **timestamp:** ISO 8601 UTC, normalizado por `Instant.toString()` (por exemplo `2026-11-16T04:12:00Z`).
- **Sem rate limiting:** o limite do CT17 protege a cota da CoinGecko, que não está em jogo aqui. O Blockscout público aceita 180 requisições por minuto, e o External Data Service guarda o resultado por endereço durante 12 s (`HISTORY_CACHE_SECONDS`).

## Nota [Verificado em 19/11/2026]

Conferido com o Core API e o External Data Service no Docker, ligado ao Blockscout real, por `curl` (seção 11.3 do desenho):

- **Usuário novo:** HTTP 200 `{"ethereumAddress":"…","network":"SEPOLIA","transactions":[]}`.
- **Carteira de demonstração** (endereço posto, só para leitura, no `app_user` de um usuário de teste e restaurado em seguida): HTTP 200 com uma lista não vazia, todos os itens com campos válidos e `amountEth` como número JSON em notação plana.
- **Sem token:** HTTP 401 TOKEN_INVALID.
- **External Data Service inalcançável** (`core-api` temporário com `EXTERNAL_DATA_SERVICE_URL` para um host inexistente): HTTP 502 HISTORY_UNAVAILABLE, com log `CT24 fetchAddressTransactions: falha de rede (ConnectException)`.
