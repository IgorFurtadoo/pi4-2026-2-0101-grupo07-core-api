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
| Corpo ausente, JSON malformado ou campo com tipo errado (por exemplo `destinationAddress` numérico ou `amountEth` booleano) | HTTP 400, `code` REQUEST_INVALID |
| `destinationAddress` ausente, com formato inválido ou recusado pelo Blockchain Service (checksum EIP-55) | HTTP 400, `code` ADDRESS_INVALID |
| `amountEth` ausente, zero, negativo, com mais de 18 casas decimais ou com mais de 20 dígitos inteiros | HTTP 400, `code` AMOUNT_INVALID |
| Blockchain Service indisponível ou com resposta inesperada (nada é gravado) | HTTP 502, `code` BLOCKCHAIN_UNAVAILABLE |
| Falha inesperada, incluindo Banco do Core API indisponível | HTTP 500, `code` INTERNAL_ERROR |

Nota (emenda do H05.2, decidida pelo responsável do módulo (Igor)):
- **Corpo inválido:** corpo ausente, JSON malformado, raiz que não é objeto ou campo com tipo errado responde 400 REQUEST_INVALID, como no CT1 e no CT3. Número com mais de 1000 dígitos também. Campo ausente ou `null` continua com o código do próprio campo.
- **Origem:** o endereço de origem é sempre o da carteira do usuário do token (CT2 `app_user`). O Frontend não informa origem; `sourceAddress`, `userId` ou qualquer outro campo extra no corpo é ignorado.
- **Validação:** o Core API confere o formato do endereço (`0x` e 40 hexadecimais) e a regra do valor (maior que 0, até 18 casas decimais e até 20 dígitos inteiros; zeros à direita não contam, então `0.0100000000000000000000` é aceito como `0.01` e `1E+2` como `100`) antes de chamar o Blockchain Service. O checksum EIP-55 do endereço e a mesma regra do valor também são conferidos pelo Blockchain Service (CT10), e os 400 ADDRESS_INVALID e AMOUNT_INVALID dele são repassados com o mesmo código e com a mensagem do Core API. Se o endereço e o valor forem inválidos ao mesmo tempo, a resposta é ADDRESS_INVALID (o endereço é conferido primeiro).
- **Usuário removido:** se o usuário do token não existir mais em CT2 `app_user`, a resposta é HTTP 401, `code` TOKEN_INVALID, igual à do token inválido. O CT10 não é chamado.
- **Resposta inesperada do Blockchain Service:** qualquer outra falha ou resposta do CT10 que não seja a esperada (tempo esgotado, conexão recusada, 400 com outro código, 401, 404, 5xx, ou 202 com corpo fora do formato) responde HTTP 502, `code` BLOCKCHAIN_UNAVAILABLE, e nada é gravado em CT12 `eth_transaction`. O CT10 é chamado uma única vez, sem retentativa.
- **Falha ao gravar:** se a gravação em CT12 `eth_transaction` falhar depois de o Blockchain Service criar a task, a resposta é HTTP 500, `code` INTERNAL_ERROR, e a task segue no Blockchain Service sem registro no Core API. É uma limitação conhecida: o CT10 não tem idempotência.
- **amountEth em texto:** `amountEth` pode vir como número JSON (`0.01`) ou como texto numérico (`"0.01"`), lido como decimal exato. O texto permite ao Frontend preservar a precisão que o `JSON.parse` do navegador perde em números com muitos dígitos. Texto vazio conta como ausente (AMOUNT_INVALID); texto que não é número responde REQUEST_INVALID.
