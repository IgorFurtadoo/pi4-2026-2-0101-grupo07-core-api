# ADR 0004: autenticar com JWT HS256 e senha em bcrypt

**Status:** aceito

**Contexto:** O Core API é o ponto único de contato do Frontend. A premissa P1 define que ele emite um token JWT no login e o exige em todas as rotas do Frontend, menos no cadastro e no próprio login; o webhook do Blockchain Service usa outra credencial, a `X-Service-Key` (CT15). O contrato CT3 exige a mesma resposta (`INVALID_CREDENTIALS`) para e-mail inexistente e senha errada, para não revelar quais e-mails estão cadastrados, e o CT2 guarda só o hash da senha. Há uma única instância do Core API, e ela é a única que emite e a única que valida o token. O sistema roda em docker-compose, sem infraestrutura de nuvem, na Sepolia Testnet, sem valor real. A equipe adotou como diretriz não introduzir dependências sem necessidade comprovada, e o projeto não usa Spring. Ficaram fora do escopo: recuperação de senha, logout no servidor e limite de tentativas de login. A forma como o Frontend guarda o token no navegador é decisão do módulo dele e ainda está em aberto.

**Decisão:**

- **Token:** JWT assinado com HS256, emitido pelo próprio Core API, sem estado no servidor, com a biblioteca `com.auth0:java-jwt` (versão 4.6.2 no `pom.xml`). O token traz só três claims: `sub` (o id do usuário, em texto), `iat` e `exp`. Não traz e-mail, endereço da carteira nem identificador de sessão. A validade vem de `JWT_TTL_MINUTES` (240 minutos no `.env.example`). O segredo vem de `JWT_SECRET`, com no mínimo 32 bytes, e o serviço não sobe com o valor de exemplo do `.env.example`.
- **Validação:** o algoritmo é fixo em HS256; token com `"alg": "none"` ou assinado com outro algoritmo (HS384, HS512) é recusado. A assinatura e o `exp` são conferidos sem tolerância de relógio. A biblioteca só confere o `exp` quando ele existe, então o `JwtService` exige o `exp` por conta própria. O `sub` só vale como texto com um inteiro decimal positivo que caiba em `long`. Qualquer falha vira 401 `TOKEN_INVALID`, e nem o token nem a mensagem da biblioteca vão ao log.
- **Filtro:** a proteção vale por padrão. Toda rota exige `Authorization: Bearer <token>`, menos uma lista explícita de rotas públicas (`POST /users` e `POST /auth/login`). Uma rota nova esquecida fica protegida, nunca aberta. O filtro não consulta o banco. Se o usuário do token não existir mais, cada rota que lê o usuário (a partir do saldo, no passo C4) responde 401 `TOKEN_INVALID`.
- **Senha:** bcrypt de custo 12 (versão `2a`) com a biblioteca `at.favre.lib:bcrypt` (versão 0.10.2 no `pom.xml`). O bcrypt só usa os primeiros 72 bytes da senha. Em vez de truncar em silêncio, o cadastro recusa senha acima de 72 bytes em UTF-8 (`PASSWORD_INVALID`), e a biblioteca está configurada para lançar exceção se mesmo assim receber uma.
- **Login:** e-mail inexistente e senha errada recebem a mesma resposta (401, `INVALID_CREDENTIALS`, "E-mail ou senha incorretos") e levam o mesmo tempo. Quando o e-mail não existe, a senha é conferida contra um hash falso, gerado na subida com o mesmo `PasswordHasher` e, portanto, com o mesmo custo. O login não revela regra de formato: uma senha acima de 72 bytes também recebe `INVALID_CREDENTIALS`.

**Alternativas consideradas:**

- Sessão opaca guardada no banco (um identificador aleatório no cliente e a sessão numa tabela): permitiria revogar uma sessão e fazer logout de verdade no servidor. Foi descartada porque exige uma consulta ao banco em toda requisição autenticada, uma tabela a mais e a limpeza das sessões vencidas, ou seja, estado no servidor, sem que revogação seja requisito do projeto.
- Biblioteca `jjwt`: funciona, mas são três artefatos (`jjwt-api`, `jjwt-impl` e `jjwt-jackson`). A `java-jwt` é um artefato só, usa o Jackson que o projeto já tem (não trouxe nenhuma dependência transitiva nova) e permite fixar o algoritmo na validação.
- Assinatura assimétrica (RS256 ou ES256): o ganho dela aparece quando quem valida o token é diferente de quem o emite, porque os validadores recebem só a chave pública. Aqui só o Core API emite e valida, então o ganho não existe, e o projeto passaria a gerenciar um par de chaves em vez de um segredo.
- Argon2 (`argon2-jvm`): é o algoritmo mais recente para senhas, mas a biblioteca chama uma biblioteca nativa pela JNA (o jar traz um `libargon2` por sistema operacional e arquitetura). Isso é um ponto a mais para acertar na imagem Docker, e o bcrypt de custo 12 basta para o projeto.
- jBCrypt (`org.mindrot:jbcrypt`): está abandonado; a última versão no Maven Central é a 0.4, de fevereiro de 2017.
- Spring Security (ou só o módulo de criptografia dele): traria o Spring a um projeto que não o usa só para fazer hash de senha e validar token, o que duas bibliotecas pequenas já resolvem.
- Token num cookie `HttpOnly`: o JavaScript da página não leria o token, o que protege contra XSS. Mas o navegador envia o cookie sozinho, o que obriga a tratar CSRF e a liberar o CORS com credenciais. Além disso, onde o Frontend guarda o token é decisão do módulo dele, hoje em aberto; o CORS do Core API não libera credenciais.
- Refresh token (um token curto e um longo para renová-lo): acrescentaria um segundo token, uma rota de renovação e a revogação do token longo, sem requisito que peça isso. Com validade de 240 minutos, quando o token vence o Frontend volta para a tela de login.

**Consequências:**

- Positivas:
  - Validar o token não consulta o banco nem guarda estado: é uma conta de HMAC por requisição.
  - O algoritmo fixo fecha os ataques de `"alg": "none"` e de troca de algoritmo.
  - O tempo de resposta e o corpo do login não revelam se um e-mail está cadastrado.
  - Nenhuma senha é truncada em silêncio.
  - Uma rota nova nasce protegida.
  - As duas bibliotecas são Java puro e cabem em classes pequenas e testáveis (`JwtService`, `PasswordHasher`, `TokenFilter`).
- Negativas e limites, declarados abertamente:
  - Não há revogação antes do vencimento. Um token roubado vale até o `exp`, ou seja, por até `JWT_TTL_MINUTES`. O "sair" do Frontend só descarta o token no navegador.
  - Não há limite de tentativas de login. Força bruta contra senhas fracas é possível; o custo do bcrypt a desacelera, mas não a impede.
  - Quem tiver o `JWT_SECRET` emite token válido para qualquer usuário. O segredo fica no ambiente do container e, portanto, visível para quem tem acesso ao Docker da máquina. Trocar o segredo invalida todos os tokens em uso, e todos precisam entrar de novo.
  - Não há HTTPS dentro do compose (rede interna, testnet). Em desenvolvimento, a porta do Core API só é publicada em `127.0.0.1`.
  - A biblioteca de bcrypt é estável, mas não tem manutenção ativa (versão 0.10.2, de 2023).
  - O token é guardado no navegador, onde e como o Frontend decidir. Se ficar acessível ao JavaScript da página, um XSS no Frontend pode lê-lo.
  - A subida do serviço faz um bcrypt de custo 12 a mais, o do hash falso.
  - Senha acima de 72 bytes é recusada no login sem rodar o bcrypt, e por isso responde mais rápido. Isso revela só que a senha é longa demais, não se o e-mail existe.
  - Essa solução só é aceitável porque o sistema opera na Sepolia Testnet, sem valor real. Em produção seriam necessários HTTPS de ponta a ponta, limite de tentativas no login, tokens mais curtos com renovação e revogação, e o segredo num cofre de segredos.
