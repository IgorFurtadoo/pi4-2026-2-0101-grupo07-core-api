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
| Corpo ausente, JSON malformado ou campo com tipo errado (por exemplo `email` numérico) | HTTP 400, `code` REQUEST_INVALID |
| `email` ausente, com formato inválido ou com mais de 254 caracteres | HTTP 400, `code` EMAIL_INVALID |
| `password` ausente, com menos de 8 caracteres ou com mais de 72 bytes em UTF-8 | HTTP 400, `code` PASSWORD_INVALID |
| Já existe usuário com o mesmo `email` | HTTP 409, `code` EMAIL_ALREADY_REGISTERED |
| Blockchain Service indisponível ou falha ao criar a carteira (o usuário não é gravado) | HTTP 502, `code` WALLET_UNAVAILABLE |
| Falha inesperada, incluindo Banco do Core API indisponível | HTTP 500, `code` INTERNAL_ERROR |

Nota (emenda do H02.2, decidida pelo responsável do módulo (Igor)):
- **Corpo inválido:** corpo ausente, JSON malformado ou campo com tipo errado responde 400 REQUEST_INVALID, como no CT3. Campo ausente continua com o código do próprio campo.
- **Senha:** o limite de 72 bytes em UTF-8 vem do bcrypt, que ignora o que passa disso. A senha mais longa é recusada, em vez de truncada.
- **E-mail:** antes de validar, conferir e gravar, o e-mail é normalizado (espaços nas pontas removidos e letras em minúsculas). O formato aceito é `algo@algo.algo` sem espaços, com no máximo 254 caracteres. `Ana@Example.com` e ` ana@example.com ` são o mesmo e-mail: o segundo cadastro recebe 409. A saída devolve o e-mail normalizado.
