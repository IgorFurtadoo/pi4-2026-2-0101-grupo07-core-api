# CLAUDE.md — Core API (Lemma)

Este repositório é o **Core API** (Container 2) da carteira custodial Lemma, Projeto Integrador do Grupo 07 (PUC-Campinas). É o ponto único de contato do Frontend: cadastro, login com JWT, saldo, envio, acompanhamento de transações e dados de mercado. Fala com o Blockchain Service e com o External Data Service; **não** fala com a Ethereum nem com a CoinGecko. Use só a **Sepolia Testnet**; nunca mainnet nem valores reais.

Idioma: comunique-se em português do Brasil. Nomes em código (classes, métodos, campos, endpoints, enums, códigos de erro) em inglês. Comentários e documentação em português.

## Leitura obrigatória antes de qualquer código

1. `docs/desenho-core-api.md`: desenho do módulo (stack, camadas, modelo de dados, fluxos, segurança, mapa de erros, ordem de implementação na seção 13). As decisões 1 a 10 da seção 12 já foram tomadas pelo responsável do módulo; não as reabra.
2. `docs/contratos/`: fichas dos contratos (CT) deste e dos outros módulos. É a fonte de verdade para campos, tipos, status HTTP e códigos de erro. **Não invente formato de campo, status HTTP ou código de erro**: copie da ficha. Onde o desenho traz uma emenda a uma ficha (seção 12), a emenda é feita no passo indicado, no mesmo commit do código que a usa.

Em caso de divergência entre o desenho e as fichas, pare e pergunte.

## Como trabalhar

- **Um passo por vez**, na ordem da seção 13 do desenho (C1 a C9). Antes de escrever código, apresente um plano curto (arquivos a criar, commits previstos, dúvidas) e espere aprovação.
- **Simplicidade primeiro.** Sem Spring, sem Lombok, sem Flyway, sem injeção de dependência, sem interface por service ou repository, sem padrões que o desenho não pede. A única interface prevista é `BlockchainClient` (para testes dos services). DTOs são `record`.
- **Dependências permitidas, por passo:** Javalin, Jackson, SLF4J + Logback e JUnit 5 desde o C1; Hibernate ORM com `hibernate-hikaricp` e driver MySQL a partir do C2; `at.favre.lib:bcrypt` a partir do C2; `com.auth0:java-jwt` a partir do C3. Cliente HTTP é o `java.net.http.HttpClient` do JDK. Rate limiting é classe própria. Qualquer outra: pergunte antes. Não antecipe dependência de um passo futuro.
- **Camadas** (`controller → service → repository`, `service → client`), conforme a seção 4 do desenho. Controller não tem regra de negócio; service não conhece Javalin; entidade não sai no JSON. Cada método de repository abre e fecha a própria transação; nenhuma transação fica aberta durante chamada a outro módulo.
- **Convenções:** JSON em `camelCase`; banco em `snake_case`, tabelas no singular; enums e códigos de erro em `UPPER_SNAKE_CASE`; datas em ISO 8601 UTC; ETH em `BigDecimal` (nunca `double`), serializado com `toPlainString()`; erros HTTP no formato `{ "code": "...", "message": "..." }`.
- Código que você escreve deve ser simples o bastante para ser explicado à banca. Prefira clareza a esperteza.

## O que você NÃO decide sozinho (pare e pergunte)

- Qualquer item "[Aberto]" do desenho (seção 12, "Em aberto"): idempotência no CT9/CT10, tempo limite de task e retentativa do webhook, External Data Service, compose do sistema, limite de tentativas no login, cache do mercado.
- Qualquer mudança em contrato (campo, status, código de erro) além das emendas que o desenho já lista na seção 12.
- Versão do Java, build tool ou nome do pacote base.
- Onde mora o `docker-compose` do sistema inteiro. Neste repositório, use um compose apenas para desenvolver este serviço com o seu MySQL.
- Qualquer biblioteca nova.

## Decisões de ferramenta (travadas)

- Java 21 (LTS), Maven, pacote base `com.lemma.core`.
- Banco: MySQL 8.4 com tag fixa; esquema em `schema.sql` montado em `/docker-entrypoint-initdb.d`; Hibernate em `validate`.
- Portas: Core API 8080 (publicada só em `127.0.0.1` no desenvolvimento). A porta do MySQL não é publicada.
- Jar executável: `target/core-api.jar`.

## Segurança (regras duras)

- **Nunca** coloque `JWT_SECRET`, `SERVICE_KEY`, senha de banco, senha de usuário, token ou `.env` em código, log, teste, commit ou saída de comando.
- **Nunca** logue corpo de requisição, senha, hash de senha, token, `X-Service-Key` nem URLs de outros módulos. O log traz só o contrato chamado e o código ou status devolvido.
- `.env` fica no `.gitignore`; só `.env.example` (com valores de exemplo, sem segredo real) é versionado. Os valores de exemplo de `JWT_SECRET`, `SERVICE_KEY` e `DB_PASSWORD` são recusados na subida.
- Comparação de `X-Service-Key` em tempo constante (`MessageDigest.isEqual`).
- Toda leitura de transação filtra por `taskId` **e** `userId`.
- Não leia nem edite `.env` (está bloqueado em `.claude/settings.json`).

## Git (regras duras)

- **Não altere `git config`**, não use `--author`, não crie identidade nova.
- **Não adicione `Co-Authored-By`, "Generated with Claude Code", URL de sessão nem qualquer assinatura ou menção ao Claude** em commits ou descrições de PR. (Também desligado em `.claude/settings.json`; esta regra vale mesmo assim.)
- **Nunca faça `git push` nem `git merge`.**
- Não use `--amend`, `rebase`, `reset --hard` nem `push --force` sem pedido explícito.
- **Uma branch por passo**, a partir de `main`: `h01-5-esqueleto-core-api`, etc.
- **Commits pequenos**, um por passo lógico. Mensagem em português, no imperativo, só com conteúdo técnico, e uma linha final de rodapé com o card:

  ```
  H03.1: emitir e validar token JWT (CT3)

  OP#871
  ```

- Só commite com `mvn clean test` verde e o código compilando. Cada commit deve compilar sozinho.
- Antes de cada commit, rode `git status` e confira que não há arquivo inesperado (`.env`, `target/`, `.idea/`).
- Ao terminar um passo, informe: o que foi feito, comandos de verificação e o critério "Pronto quando" cumprido, e pare.

## Passos e cards (OpenProject)

| Passo | O que é | Card (OP) |
|---|---|---|
| C1 | Esqueleto, compose, esquema, `AppConfig` | H01.5 #1315, H01.2 #865 (parte Core API) |
| C2 | Cadastro (CT1) | H02.2 #869 |
| C3 | Login, JWT, filtro de token, CORS, ADR 0004 | H03.1 #871 |
| C4 | Saldo (CT6) | H04.2 #874 |
| C5 | Envio (CT9) | H05.2 #877 |
| C6 | Acompanhamento e webhook (CT13, CT15) | H08.2 #886 |
| C7 | Mercado (CT17) contra servidor falso | H06.2 #880 |
| C8 | Rate limiting | H10.1 #882 |
| C9 | Integração ponta a ponta com o Blockchain Service | H07.2 #884, H04.4 #1317 (parte) |

Critério de pronto de cada passo: coluna "Pronto quando" da seção 13 do desenho.

## Comandos

Pré-requisitos: Java 21, Maven, Docker com Compose (usuário no grupo `docker`).

Configuração inicial (uma vez):

```bash
cp .env.example .env
# no .env, troque DB_PASSWORD e SERVICE_KEY e gere o segredo do JWT:
openssl rand -base64 48   # cole a saída em JWT_SECRET
```

O `.env.example` traz valores de exemplo propositalmente recusados: o serviço não sobe até `JWT_SECRET`, `SERVICE_KEY` e `DB_PASSWORD` serem trocados. A `SERVICE_KEY` deve ser o mesmo valor configurado no Blockchain Service.

| Para quê | Comando |
|---|---|
| Criar a rede compartilhada com o Blockchain Service (uma vez por máquina, antes do primeiro `docker compose up`) | `docker network create lemma-dev` |
| Testes | `mvn clean test` |
| Build do jar (`target/core-api.jar`) | `mvn package` |
| Build da imagem | `docker compose build` |
| Subir serviço e banco | `docker compose up --build` (ou `-d` em segundo plano) |
| Ver estado e logs | `docker compose ps` / `docker compose logs -f core-api` |
| Testar se está no ar | `curl -i http://127.0.0.1:8080/` |
| Testar a falha de configuração (sai com código 1) | `docker compose run --rm --no-deps -e JWT_SECRET= core-api` |
| Ver as tabelas | `docker compose exec core-db sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u "$MYSQL_USER" core -e "SHOW TABLES"'` |
| Derrubar | `docker compose down` |
| Derrubar e apagar o banco (o `schema.sql` roda de novo no próximo `up`) | `docker compose down -v`. **Apaga todos os usuários e transações do banco do Core API.** Para mudar o esquema num volume existente, use `ALTER TABLE`. |

**Uma única instância do serviço.** O rate limiting fica em memória (seção 8.6 do desenho) e só funciona assim.
