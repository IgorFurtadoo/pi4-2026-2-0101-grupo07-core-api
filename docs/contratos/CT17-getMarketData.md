# CT17: getMarketData

| Linha | Valor |
|---|---|
| Fronteira | HTTP |
| Quem chama | Frontend |
| Quem responde | Core API |
| Atende | E6 e E7 |
| Como chamar | `GET /market/{assetId}` |

Devolve o preço atual e a série dos últimos 7 dias, em USD. O Core API aplica rate limiting antes de chamar o External Data Service (CT18). O valor do limite é configuração do Core API e fica abaixo do limite do plano Demo da CoinGecko (premissa P5).

## Entrada

| Campo | Tipo | Obrigatório | Exemplo |
|---|---|---|---|
| Authorization (cabeçalho) | texto | sim | Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.Kq8vN2xT5mYpR7wZ1cLd3hJfA9sUeB4oGiVtXyW6nMk |
| assetId (caminho) | texto | sim | ethereum |

## Saída

Resposta `200`.

| Campo | Tipo | Obrigatório | Exemplo |
|---|---|---|---|
| assetId | texto | sim | ethereum |
| currency | um de: USD | sim | USD |
| currentPrice | decimal | sim | 3200.45 |
| series | lista de (objeto (PricePoint)) | sim | lista de pontos de preço dos últimos 7 dias |

Campos de `objeto (PricePoint)`:

| Campo | Tipo | Obrigatório | Exemplo |
|---|---|---|---|
| timestamp | data e hora | sim | 2026-10-19T12:00:00Z |
| price | decimal | sim | 3187.9 |

## Erros

| Quando | O que volta |
|---|---|
| Token ausente, inválido ou expirado | HTTP 401, `code` TOKEN_INVALID |
| `assetId` desconhecido | HTTP 404, `code` ASSET_NOT_FOUND |
| Limite de requisições do Core API excedido | HTTP 429, `code` RATE_LIMIT_EXCEEDED, com o cabeçalho `Retry-After` (inteiro, em segundos) |
| External Data Service ou CoinGecko API indisponível ou com falha | HTTP 502, `code` MARKET_DATA_UNAVAILABLE |
| Falha inesperada | HTTP 500, `code` INTERNAL_ERROR |
