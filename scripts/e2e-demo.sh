#!/usr/bin/env bash
# Roteiro ponta a ponta do Core API contra o Blockchain Service real, na Sepolia
# (passo C9; seção 11, item 6 do desenho). Serve de demonstração para a banca:
# cada passo é impresso e qualquer falha encerra com código 1.
#
#   0. pré-voo: containers, URL do webhook no Blockchain Service, chaves iguais
#   1. cadastro, login e saldo zero (CT1, CT3, CT6)
#   2. saldo da carteira de demonstração pelo CT7, direto no Blockchain Service
#   3. abastecimento: CT10 direto (demonstração -> usuário). O webhook dessa task
#      recebe 404 (o Core API não tem a linha); o roteiro insere a linha PENDING e
#      prova que o GET /transactions/{taskId} a recupera pela consulta ativa (CT14)
#   4. saldo diferente de zero pelo CT6, igual ao CT7
#   5. envio 1 (CT9) sem nenhum GET /transactions: a linha vira CONFIRMED pelo
#      webhook (CT15); só depois o GET confere
#   6. saldo depois do envio 1 e preço do gás inferido
#   7. envio 2 com GET em paralelo e em laço curto até depois do webhook
#   8. saldos finais, hashes e gás
#   9. conferência do log do core-api (nenhum segredo)
#
# Uso: DEMO_WALLET_ADDRESS=0x... scripts/e2e-demo.sh
#   (com o core-api e o core-db no ar, e o Blockchain Service na rede lemma-dev)
#
# Variáveis:
#   DEMO_WALLET_ADDRESS      obrigatória: carteira custodial do Blockchain Service já
#                            abastecida (endereço público). Sem valor padrão
#   CORE_API_URL             padrão http://127.0.0.1:8080
#   FUND_ETH                 abastecimento do usuário; padrão 0.003, no máximo 0.005
#   SEND_ETH                 valor de cada um dos 2 envios; padrão 0.0005, no máximo 0.0005
#   CONFIRM_TIMEOUT_SECONDS  espera de cada confirmação; padrão 180
#   RACE_LOOPS               laços de GET em paralelo no envio 2; padrão 8
#   RACE_INTERVAL            pausa entre GETs de um laço, em segundos; padrão 0.1
#   CORE_API_CONTAINER, CORE_DB_CONTAINER, BLOCKCHAIN_CONTAINER: nomes dos containers
#
# Custo: o abastecimento e os dois envios são transações reais na Sepolia. Os
# envios voltam para a carteira de demonstração (só o gás se perde); o que sobra
# do abastecimento fica na carteira do usuário de teste. O usuário
# c9-teste-<aleatório>@example.com fica no banco.
#
# Segredos: a SERVICE_KEY e a URL do Blockchain Service são usadas só dentro do
# container core-api (ambiente dele), e a chave vai ao curl pela entrada padrão. A
# senha do banco só existe dentro do core-db. A senha e o token do usuário de teste
# ficam em variáveis do shell e vão ao curl pela entrada padrão; nunca são
# impressos. Não use "bash -x" aqui.
set -euo pipefail

CORE_API_URL="${CORE_API_URL:-http://127.0.0.1:8080}"
FUND_ETH="${FUND_ETH:-0.003}"
SEND_ETH="${SEND_ETH:-0.0005}"
CONFIRM_TIMEOUT_SECONDS="${CONFIRM_TIMEOUT_SECONDS:-180}"
RACE_LOOPS="${RACE_LOOPS:-8}"
RACE_INTERVAL="${RACE_INTERVAL:-0.1}"
CORE_API_CONTAINER="${CORE_API_CONTAINER:-core-api-core-api-1}"
CORE_DB_CONTAINER="${CORE_DB_CONTAINER:-core-api-core-db-1}"
BLOCKCHAIN_CONTAINER="${BLOCKCHAIN_CONTAINER:-blockchain-blockchain-service-1}"

EXPECTED_WEBHOOK_URL="http://core-api:8080/webhooks/tasks"
MAX_FUND_WEI=5000000000000000        # 0.005 ETH
MAX_SEND_WEI=500000000000000         # 0.0005 ETH
MIN_DEMO_WEI=10000000000000000       # 0.01 ETH
GAS_LIMIT=500000                     # limite fixo do Blockchain Service (TaskWorker)
TRANSFER_GAS=21000                   # gás de um envio para conta que já existe
HOLD_SECONDS=12                      # mais de dois ciclos do worker (5 s)
POLL_SECONDS=3
ETHERSCAN="https://sepolia.etherscan.io/tx"

ADDRESS_REGEX='^0x[0-9a-fA-F]{40}$'
UUID_REGEX='^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
HASH_REGEX='^0x[0-9a-fA-F]{64}$'
AMOUNT_REGEX='^[0-9]+(\.[0-9]{1,18})?$'

# --- Funções -----------------------------------------------------------------

fail() {
  echo "FALHA $*" >&2
  exit 1
}

ok() {
  echo "OK    $*"
}

step() {
  echo
  echo "== Passo $*"
}

# ETH em notação plana -> wei (inteiro de 64 bits: até 9 ETH). Acima disso, vazio.
to_wei() {
  local value="$1" int frac=""
  int="${value%%.*}"
  [[ "$value" == *.* ]] && frac="${value#*.}"
  if [ "${#int}" -gt 1 ] || [ "$int" -ge 9 ]; then
    return 0
  fi
  frac="${frac}000000000000000000"
  echo $((10#$int * 1000000000000000000 + 10#${frac:0:18}))
}

# wei -> ETH em notação plana, sem zeros à direita.
from_wei() {
  local wei="$1" sign="" frac
  if [ "$wei" -lt 0 ]; then sign="-"; wei=$((-wei)); fi
  frac="$(printf '%018d' $((wei % 1000000000000000000)))"
  frac="${frac%"${frac##*[!0]}"}"
  echo "$sign$((wei / 1000000000000000000))${frac:+.$frac}"
}

# Preço do gás em wei por unidade, e em gwei com 9 casas (só para leitura).
gas_price_text() {
  awk -v w="$1" 'BEGIN { printf "%d wei (%.9f gwei)", w, w / 1e9 }'
}

# Campo de texto ou número do último corpo ($BODY); vazio se não houver.
text_field() {
  if [[ "$BODY" =~ \"$1\":\"([^\"]*)\" ]]; then printf '%s' "${BASH_REMATCH[1]}"; fi
}
number_field() {
  if [[ "$BODY" =~ \"$1\":([0-9][0-9.]*)[,}] ]]; then printf '%s' "${BASH_REMATCH[1]}"; fi
}

# Rota do Frontend com token. O corpo (sem segredo) vai no argumento; o token, pela
# entrada padrão. Deixa $STATUS e $BODY.
api() {
  rm -f "$WORK/body"
  local args=(-s --max-time 30 -o "$WORK/body" -w '%{http_code}' -X "$1")
  if [ "$#" -ge 3 ]; then
    args+=(-H 'Content-Type: application/json' --data-binary "$3")
  fi
  STATUS="$(printf 'Authorization: Bearer %s\n' "$TOKEN" |
    curl "${args[@]}" -H @- "$CORE_API_URL$2")" || STATUS=000
  BODY="$(cat "$WORK/body" 2>/dev/null || true)"
}

# Cadastro ou login: o corpo leva a senha, então vai pela entrada padrão.
credentials_post() {
  STATUS="$(printf '{"email":"%s","password":"%s"}' "$EMAIL" "$PASSWORD" |
    curl -s --max-time 30 -o "$WORK/body" -w '%{http_code}' \
      -H 'Content-Type: application/json' --data-binary @- "$CORE_API_URL$1")" || STATUS=000
  BODY="$(cat "$WORK/body" 2>/dev/null || true)"
}

# Chamada direta ao Blockchain Service (CT7, CT10, CT14), feita de dentro do
# container core-api com a SERVICE_KEY e a BLOCKCHAIN_SERVICE_URL do ambiente dele.
# bs <método> <caminho> [corpo]. Deixa $STATUS e $BODY.
bs() {
  local out
  # shellcheck disable=SC2016
  out="$(docker exec "$CORE_API_CONTAINER" sh -c '
    method="$1"; path="$2"
    if [ "$#" -ge 3 ]; then
      printf "X-Service-Key: %s\n" "$SERVICE_KEY" | curl -s --max-time 20 -w "\n%{http_code}" \
        -X "$method" -H @- -H "Content-Type: application/json" --data-binary "$3" \
        "$BLOCKCHAIN_SERVICE_URL$path"
    else
      printf "X-Service-Key: %s\n" "$SERVICE_KEY" | curl -s --max-time 20 -w "\n%{http_code}" \
        -X "$method" -H @- "$BLOCKCHAIN_SERVICE_URL$path"
    fi' _ "$@")" || out=$'\n000'
  STATUS="${out##*$'\n'}"
  BODY="${out%$'\n'*}"
}

# sql <consulta>: roda no core-db; saída sem cabeçalho, separada por tabulação.
# Todo valor interpolado foi validado por regex antes.
sql() {
  # shellcheck disable=SC2016
  docker exec "$CORE_DB_CONTAINER" sh -c \
    'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u "$MYSQL_USER" core -N -B -e "$1"' _ "$1"
}

# Linha local: "<status> <hash ou -> <motivo ou -> <updated_at com ms>" (vazio se não existe).
db_row() {
  sql "SELECT status, IFNULL(transaction_hash,'-'), IFNULL(failure_reason,'-'),
              DATE_FORMAT(updated_at, '%Y-%m-%dT%H:%i:%s.%f')
         FROM eth_transaction WHERE task_id = '$1'" | tr '\t' ' '
}

# Saldo pelo CT7, direto no Blockchain Service. Deixa o valor em $CT7_BALANCE.
ct7_balance() {
  bs GET "/wallets/$1/balance"
  [ "$STATUS" = "200" ] || fail "CT7 do endereço $1 respondeu $STATUS $BODY"
  CT7_BALANCE="$(number_field balanceEth)"
  [ -n "$CT7_BALANCE" ] || fail "CT7 sem balanceEth numérico: $BODY"
}

# Saldo pelo CT6 do Core API: número JSON em notação plana, igual ao CT7.
# Deixa o valor em $CT6_BALANCE.
ct6_balance_matches_ct7() {
  api GET /wallet/balance
  [ "$STATUS" = "200" ] || fail "GET /wallet/balance respondeu $STATUS $BODY"
  [[ "$BODY" =~ \"balanceEth\":([0-9]+(\.[0-9]+)?)[,}] ]] ||
    fail "balanceEth não é número JSON em notação plana: $BODY"
  CT6_BALANCE="${BASH_REMATCH[1]}"
  ct7_balance "$USER_ADDRESS"
  [ "$(to_wei "$CT6_BALANCE")" = "$(to_wei "$CT7_BALANCE")" ] ||
    fail "saldo do Core API ($CT6_BALANCE) difere do CT7 ($CT7_BALANCE)"
  ok "GET /wallet/balance -> $BODY"
  ok "igual ao CT7 direto no Blockchain Service ($CT7_BALANCE)"
}

# Espera a task chegar a estado final pelo CT14 direto, sem passar pelo Core API.
# Deixa $TASK_STATUS, $TASK_HASH e $TASK_REASON.
wait_task_final() {
  local task="$1" t0="$SECONDS" shown_hash=""
  while :; do
    bs GET "/tasks/$task"
    if [ "$STATUS" = "200" ]; then
      TASK_STATUS="$(text_field status)"
      TASK_HASH="$(text_field transactionHash)"
      TASK_REASON="$(text_field failureReason)"
      if [ -n "$TASK_HASH" ] && [ -z "$shown_hash" ]; then
        shown_hash="$TASK_HASH"
        echo "      t=$((SECONDS - t0))s enviada: $ETHERSCAN/$TASK_HASH"
      fi
      case "$TASK_STATUS" in
        CONFIRMED) echo "      t=$((SECONDS - t0))s CONFIRMED"; return 0 ;;
        FAILED) fail "task $task terminou FAILED ($TASK_REASON)" ;;
      esac
    fi
    [ $((SECONDS - t0)) -lt "$CONFIRM_TIMEOUT_SECONDS" ] ||
      fail "task $task sem estado final em ${CONFIRM_TIMEOUT_SECONDS} s (último CT14: $STATUS $BODY)"
    sleep "$POLL_SECONDS"
  done
}

# Espera até 30 s uma linha no log de um container desde o início do roteiro.
wait_log_line() { # wait_log_line <container> <texto>
  local t0="$SECONDS"
  while [ $((SECONDS - t0)) -lt 30 ]; do
    if docker logs --since "$START_TS" "$1" 2>&1 | grep -qF -- "$2"; then
      return 0
    fi
    sleep 2
  done
  return 1
}

# Um laço de GET /transactions/{taskId} do envio 2. Cada linha do arquivo:
# "<ms> <código> <status> <hash ou -> <motivo ou -> <updatedAt ou ->".
race_loop() {
  local n="$1" task="$2" code body status hash reason updated
  while [ ! -e "$WORK/stop" ]; do
    code="$(printf 'Authorization: Bearer %s\n' "$TOKEN" | curl -s -o "$WORK/race-body-$n" \
      --max-time 15 -w '%{http_code}' -H @- "$CORE_API_URL/transactions/$task")" || code=000
    body="$(cat "$WORK/race-body-$n" 2>/dev/null || true)"
    status=-; hash=-; reason=-; updated=-
    [[ "$body" =~ \"status\":\"([A-Z_]+)\" ]] && status="${BASH_REMATCH[1]}"
    [[ "$body" =~ \"transactionHash\":\"(0x[0-9a-fA-F]{64})\" ]] && hash="${BASH_REMATCH[1]}"
    [[ "$body" =~ \"failureReason\":\"([A-Z_]+)\" ]] && reason="${BASH_REMATCH[1]}"
    [[ "$body" =~ \"updatedAt\":\"([^\"]+)\" ]] && updated="${BASH_REMATCH[1]}"
    echo "$(date +%s%3N) $code $status $hash $reason $updated" >> "$WORK/race-$n"
    sleep "$RACE_INTERVAL"
  done
}

# --- Validação da entrada -------------------------------------------------------

DEMO_WALLET_ADDRESS="${DEMO_WALLET_ADDRESS:-}"
[[ "$DEMO_WALLET_ADDRESS" =~ $ADDRESS_REGEX ]] ||
  { echo "Defina DEMO_WALLET_ADDRESS (0x seguido de 40 hexadecimais)." >&2; exit 2; }
[[ "$FUND_ETH" =~ $AMOUNT_REGEX && "$SEND_ETH" =~ $AMOUNT_REGEX ]] ||
  { echo "FUND_ETH e SEND_ETH: números decimais em ETH, até 18 casas." >&2; exit 2; }
FUND_WEI="$(to_wei "$FUND_ETH")"
SEND_WEI="$(to_wei "$SEND_ETH")"
if [ -z "$FUND_WEI" ] || [ "$FUND_WEI" -le 0 ] || [ "$FUND_WEI" -gt "$MAX_FUND_WEI" ]; then
  echo "FUND_ETH deve ser maior que zero e no máximo 0.005." >&2
  exit 2
fi
if [ "$SEND_WEI" -le 0 ] || [ "$SEND_WEI" -gt "$MAX_SEND_WEI" ]; then
  echo "SEND_ETH deve ser maior que zero e no máximo 0.0005." >&2
  exit 2
fi
[ $((2 * SEND_WEI)) -lt "$FUND_WEI" ] || { echo "FUND_ETH precisa cobrir os dois envios." >&2; exit 2; }
[[ "$CONFIRM_TIMEOUT_SECONDS" =~ ^[1-9][0-9]*$ && "$RACE_LOOPS" =~ ^[1-9][0-9]?$ &&
   "$RACE_INTERVAL" =~ ^[0-9]+(\.[0-9]+)?$ ]] ||
  { echo "CONFIRM_TIMEOUT_SECONDS, RACE_LOOPS ou RACE_INTERVAL inválido." >&2; exit 2; }

WORK="$(mktemp -d)"
cleanup() {
  touch "$WORK/stop" 2>/dev/null || true
  jobs -p | xargs -r kill 2>/dev/null || true
  rm -rf "$WORK"
}
trap cleanup EXIT
START_TS="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
TOKEN=""

echo "Roteiro ponta a ponta do Core API na Sepolia (gasta ETH de testnet)."
echo "Abastecimento $FUND_ETH ETH; 2 envios de $SEND_ETH ETH de volta para a demonstração."

# --- 0. Pré-voo -------------------------------------------------------------------
step "0: pré-voo"
for c in "$CORE_API_CONTAINER" "$CORE_DB_CONTAINER" "$BLOCKCHAIN_CONTAINER"; do
  [ "$(docker inspect -f '{{.State.Running}}' "$c" 2>/dev/null)" = "true" ] || fail "container $c fora do ar"
done
ok "containers no ar: $CORE_API_CONTAINER, $CORE_DB_CONTAINER, $BLOCKCHAIN_CONTAINER"

WEBHOOK_URL="$(docker exec "$BLOCKCHAIN_CONTAINER" printenv CORE_API_WEBHOOK_URL || true)"
[ "$WEBHOOK_URL" = "$EXPECTED_WEBHOOK_URL" ] ||
  fail "CORE_API_WEBHOOK_URL do Blockchain Service é '$WEBHOOK_URL', esperado $EXPECTED_WEBHOOK_URL"
ok "Blockchain Service avisa em $EXPECTED_WEBHOOK_URL"

# Só os resumos SHA-256 saem dos containers, e nem eles são impressos.
# shellcheck disable=SC2016
KEY_A="$(docker exec "$CORE_API_CONTAINER" sh -c 'printf %s "$SERVICE_KEY" | sha256sum')"
# shellcheck disable=SC2016
KEY_B="$(docker exec "$BLOCKCHAIN_CONTAINER" sh -c 'printf %s "$SERVICE_KEY" | sha256sum')"
[ -n "${KEY_A%% *}" ] && [ "${KEY_A%% *}" = "${KEY_B%% *}" ] ||
  fail "a SERVICE_KEY do Core API difere da do Blockchain Service"
ok "SERVICE_KEY igual nos dois serviços (comparada por SHA-256, sem imprimir)"

# --- 1. Cadastro, login e saldo zero ---------------------------------------------
step "1: cadastro, login e saldo zero (CT1, CT3, CT6)"
EMAIL="c9-teste-$(od -An -tx1 -N4 /dev/urandom | tr -d ' \n')@example.com"
PASSWORD="$(head -c 18 /dev/urandom | base64 | tr -d '/+=')"
credentials_post /users
[ "$STATUS" = "201" ] || fail "POST /users respondeu $STATUS $BODY"
USER_ID="$(number_field userId)"
USER_ADDRESS="$(text_field ethereumAddress)"
[[ "$USER_ID" =~ ^[0-9]+$ && "$USER_ADDRESS" =~ $ADDRESS_REGEX ]] || fail "resposta do cadastro fora do formato"
ok "POST /users -> 201, userId $USER_ID, carteira $USER_ADDRESS"

credentials_post /auth/login
[ "$STATUS" = "200" ] || fail "POST /auth/login respondeu $STATUS"
TOKEN="$(text_field token)"
[ -n "$TOKEN" ] || fail "login sem token"
ok "POST /auth/login -> 200 (token guardado só em variável)"

ct6_balance_matches_ct7
[ "$(to_wei "$CT6_BALANCE")" = "0" ] || fail "carteira nova com saldo $CT6_BALANCE"
ok "saldo zero"

# --- 2. Saldo da demonstração ---------------------------------------------------
step "2: saldo da carteira de demonstração (CT7 direto)"
ct7_balance "$DEMO_WALLET_ADDRESS"
DEMO_BEFORE="$CT7_BALANCE"
DEMO_BEFORE_WEI="$(to_wei "$DEMO_BEFORE")"
if [ -n "$DEMO_BEFORE_WEI" ] && [ "$DEMO_BEFORE_WEI" -lt "$MIN_DEMO_WEI" ]; then
  fail "a demonstração tem $DEMO_BEFORE ETH, menos que 0.01: nada foi enviado"
fi
ok "demonstração $DEMO_WALLET_ADDRESS com $DEMO_BEFORE ETH (mínimo 0.01)"

# --- 3. Abastecimento e consulta ativa ------------------------------------------
step "3: abastecimento pela demonstração (CT10 direto) e recuperação pela consulta ativa (CT14)"
bs POST /transactions \
  "$(printf '{"sourceAddress":"%s","destinationAddress":"%s","amountEth":%s}' \
    "$DEMO_WALLET_ADDRESS" "$USER_ADDRESS" "$FUND_ETH")"
[ "$STATUS" = "202" ] || fail "CT10 do abastecimento respondeu $STATUS $BODY"
FUND_TASK="$(text_field taskId | tr 'A-F' 'a-f')"
[[ "$FUND_TASK" =~ $UUID_REGEX ]] || fail "taskId do abastecimento fora do formato"
ok "CT10 -> 202, task $FUND_TASK (o Core API não tem linha dela)"
wait_task_final "$FUND_TASK"
FUND_HASH="$TASK_HASH"
[[ "$FUND_HASH" =~ $HASH_REGEX ]] || fail "abastecimento CONFIRMED sem hash"
ok "abastecimento CONFIRMED: $ETHERSCAN/$FUND_HASH"

wait_log_line "$CORE_API_CONTAINER" "Webhook: task $FUND_TASK desconhecida (TRANSACTION_NOT_FOUND)" ||
  fail "o log do core-api não mostrou o 404 do webhook da task $FUND_TASK em 30 s; linha não inserida"
ok "core-api respondeu 404 TRANSACTION_NOT_FOUND ao webhook (log do core-api)"
wait_log_line "$BLOCKCHAIN_CONTAINER" "Task $FUND_TASK: Core API ainda não conhece a task (HTTP 404)" ||
  fail "o log do Blockchain Service não mostrou o 404 da task $FUND_TASK em 30 s; linha não inserida"
ok "Blockchain Service registrou o 404 e não tenta de novo (log do Blockchain Service)"

ct7_balance "$DEMO_WALLET_ADDRESS"
DEMO_AFTER_FUND="$CT7_BALANCE"

# Fixture de teste: a linha que o Core API teria se o abastecimento fosse um envio dele.
sql "INSERT INTO eth_transaction (task_id, user_id, destination_address, amount_eth, status,
       transaction_hash, failure_reason, created_at, updated_at)
     VALUES ('$FUND_TASK', $USER_ID, '$USER_ADDRESS', $FUND_ETH, 'PENDING', NULL, NULL,
       UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))"
read -r ROW_STATUS _ _ ROW_INSERTED <<< "$(db_row "$FUND_TASK")"
ok "linha PENDING inserida no core-db (updated_at $ROW_INSERTED)"
echo "      esperando ${HOLD_SECONDS} s sem nenhuma chamada ao Core API..."
sleep "$HOLD_SECONDS"
read -r ROW_STATUS _ _ _ <<< "$(db_row "$FUND_TASK")"
[ "$ROW_STATUS" = "PENDING" ] ||
  fail "a linha virou $ROW_STATUS sem GET: o caminho da consulta ativa não foi exercitado"
ok "a linha segue PENDING depois de ${HOLD_SECONDS} s (o webhook não é reenviado)"

api GET "/transactions/$FUND_TASK"
[ "$STATUS" = "200" ] || fail "GET /transactions/$FUND_TASK respondeu $STATUS $BODY"
[ "$(text_field status)" = "CONFIRMED" ] && [ "$(text_field transactionHash)" = "$FUND_HASH" ] ||
  fail "GET não trouxe CONFIRMED com o hash do CT14: $BODY"
ok "GET /transactions/$FUND_TASK -> $BODY"
read -r ROW_STATUS ROW_HASH _ ROW_UPDATED <<< "$(db_row "$FUND_TASK")"
[ "$ROW_STATUS" = "CONFIRMED" ] && [ "$ROW_HASH" = "$FUND_HASH" ] && [[ "$ROW_UPDATED" > "$ROW_INSERTED" ]] ||
  fail "linha do core-db: $ROW_STATUS $ROW_HASH $ROW_UPDATED"
ok "core-db: CONFIRMED com o hash, updated_at $ROW_UPDATED (gravado pela consulta ativa)"

# --- 4. Saldo diferente de zero ---------------------------------------------------
step "4: saldo diferente de zero (CT6 contra CT7)"
ct6_balance_matches_ct7
[ "$(to_wei "$CT6_BALANCE")" = "$FUND_WEI" ] || fail "saldo $CT6_BALANCE, esperado $FUND_ETH"
ok "saldo igual ao abastecimento ($FUND_ETH)"

# --- 5. Envio 1: CONFIRMED pelo webhook, sem GET ---------------------------------
step "5: envio 1 (CT9) e CONFIRMED pelo webhook, sem nenhum GET /transactions"
api POST /transactions "$(printf '{"destinationAddress":"%s","amountEth":%s}' "$DEMO_WALLET_ADDRESS" "$SEND_ETH")"
[ "$STATUS" = "202" ] || fail "POST /transactions respondeu $STATUS $BODY"
SEND1_TASK="$(text_field taskId)"
[[ "$SEND1_TASK" =~ $UUID_REGEX ]] || fail "taskId do envio 1 fora do formato"
ok "POST /transactions -> $BODY"
echo "      acompanhando só pelo CT14 direto e pelo core-db; o Core API não recebe GET"
t0="$SECONDS"
confirmed_at=""
SEND1_HASH=""
while :; do
  bs GET "/tasks/$SEND1_TASK"
  remote_status="$(text_field status)"
  remote_hash="$(text_field transactionHash)"
  read -r ROW_STATUS ROW_HASH _ ROW_UPDATED <<< "$(db_row "$SEND1_TASK")"
  echo "      t=$((SECONDS - t0))s CT14 ${remote_status:-?} ${remote_hash:+(hash)} | core-db $ROW_STATUS"
  [ "$remote_status" = "FAILED" ] && fail "envio 1 terminou FAILED ($(text_field failureReason))"
  if [ "$ROW_STATUS" = "CONFIRMED" ]; then
    [ "$remote_status" = "CONFIRMED" ] && [ "$ROW_HASH" = "$remote_hash" ] ||
      fail "core-db CONFIRMED ($ROW_HASH) diferente do CT14 ($remote_status $remote_hash)"
    SEND1_HASH="$ROW_HASH"
    break
  fi
  if [ "$remote_status" = "CONFIRMED" ]; then
    confirmed_at="${confirmed_at:-$SECONDS}"
    [ $((SECONDS - confirmed_at)) -lt 15 ] ||
      fail "CT14 CONFIRMED há 15 s e o core-db segue $ROW_STATUS: o webhook não chegou"
  fi
  [ $((SECONDS - t0)) -lt "$CONFIRM_TIMEOUT_SECONDS" ] ||
    fail "envio 1 sem estado final em ${CONFIRM_TIMEOUT_SECONDS} s"
  sleep "$POLL_SECONDS"
done
ok "core-db CONFIRMED com hash, sem nenhum GET: quem gravou foi o webhook ($ETHERSCAN/$SEND1_HASH)"
wait_log_line "$BLOCKCHAIN_CONTAINER" "Task $SEND1_TASK: webhook CT15 entregue (CONFIRMED, HTTP 204)" ||
  fail "o log do Blockchain Service não mostrou o webhook CONFIRMED entregue"
ok "Blockchain Service: webhook CT15 entregue (CONFIRMED, HTTP 204)"
api GET "/transactions/$SEND1_TASK"
[ "$STATUS" = "200" ] && [ "$(text_field status)" = "CONFIRMED" ] &&
  [ "$(text_field transactionHash)" = "$SEND1_HASH" ] || fail "GET depois do webhook: $STATUS $BODY"
ok "GET /transactions/$SEND1_TASK -> $BODY"

# --- 6. Saldo depois do envio 1 e preço do gás ----------------------------------
step "6: saldo depois do envio 1 e preço do gás inferido"
ct6_balance_matches_ct7
BALANCE1_WEI="$(to_wei "$CT6_BALANCE")"
GAS1_WEI=$((FUND_WEI - SEND_WEI - BALANCE1_WEI))
GAS_PRICE_WEI=$((GAS1_WEI / TRANSFER_GAS))
NEEDED_WEI=$((SEND_WEI + GAS_LIMIT * GAS_PRICE_WEI * 12 / 10))
ok "gás do envio 1: $(from_wei "$GAS1_WEI") ETH; preço inferido $(gas_price_text "$GAS_PRICE_WEI")"
if [ "$BALANCE1_WEI" -lt "$NEEDED_WEI" ]; then
  fail "envio 2 pulado: o saldo $(from_wei "$BALANCE1_WEI") não cobre valor + $GAS_LIMIT x preço do gás" \
    "(+20%) = $(from_wei "$NEEDED_WEI") ETH; a rede recusaria (REJECTED_BY_NETWORK)"
fi
ok "saldo cobre o envio 2 (precisa de $(from_wei "$NEEDED_WEI") ETH adiantados)"

# --- 7. Envio 2 com GET em paralelo ----------------------------------------------
step "7: envio 2 com $RACE_LOOPS laços de GET em paralelo (consulta ativa contra webhook)"
api POST /transactions "$(printf '{"destinationAddress":"%s","amountEth":%s}' "$DEMO_WALLET_ADDRESS" "$SEND_ETH")"
[ "$STATUS" = "202" ] || fail "POST /transactions respondeu $STATUS $BODY"
SEND2_TASK="$(text_field taskId)"
[[ "$SEND2_TASK" =~ $UUID_REGEX ]] || fail "taskId do envio 2 fora do formato"
ok "POST /transactions -> $BODY"
for n in $(seq 1 "$RACE_LOOPS"); do
  race_loop "$n" "$SEND2_TASK" &
done
t0="$SECONDS"
while :; do
  read -r ROW_STATUS _ _ _ <<< "$(db_row "$SEND2_TASK")"
  [ "$ROW_STATUS" = "CONFIRMED" ] || [ "$ROW_STATUS" = "FAILED" ] && break
  [ $((SECONDS - t0)) -lt "$CONFIRM_TIMEOUT_SECONDS" ] ||
    fail "envio 2 sem estado final em ${CONFIRM_TIMEOUT_SECONDS} s"
  sleep 1
done
echo "      t=$((SECONDS - t0))s core-db $ROW_STATUS; GETs seguem por mais 5 s"
sleep 5
touch "$WORK/stop"
wait
[ "$ROW_STATUS" = "CONFIRMED" ] || fail "envio 2 terminou $ROW_STATUS"
read -r _ ROW_HASH ROW_REASON ROW_UPDATED_A <<< "$(db_row "$SEND2_TASK")"
sleep 5
read -r _ _ _ ROW_UPDATED_B <<< "$(db_row "$SEND2_TASK")"
SEND2_HASH="$ROW_HASH"
bs GET "/tasks/$SEND2_TASK"
[ "$(text_field status)" = "CONFIRMED" ] && [ "$(text_field transactionHash)" = "$SEND2_HASH" ] ||
  fail "CT14 do envio 2 difere do core-db: $BODY"

cat "$WORK"/race-[0-9]* > "$WORK/race-all"
TOTAL_GETS="$(wc -l < "$WORK/race-all")"
NON_200="$(awk '$2 != 200' "$WORK/race-all" | wc -l)"
[ "$NON_200" -eq 0 ] || fail "$NON_200 de $TOTAL_GETS GET não responderam 200"
ok "$TOTAL_GETS GET, todos 200"
FINAL_TUPLES="$(awk '$3 != "PENDING" { print $3, $4, $5, $6 }' "$WORK/race-all" | sort -u)"
EXPECTED_TUPLE="CONFIRMED $SEND2_HASH $ROW_REASON ${ROW_UPDATED_A:0:19}Z"
[ "$FINAL_TUPLES" = "$EXPECTED_TUPLE" ] ||
  fail "estados finais vistos pelos GET: [$FINAL_TUPLES], gravado: [$EXPECTED_TUPLE]"
ok "todo GET com estado final viu o mesmo: $EXPECTED_TUPLE"
REGRESSIONS=0
for f in "$WORK"/race-[0-9]*; do
  r="$(awk 'seen && ($3 " " $4 " " $5 " " $6) != final { bad++ }
            !seen && $3 != "PENDING" { seen = 1; final = $3 " " $4 " " $5 " " $6 }
            END { print bad + 0 }' "$f")"
  REGRESSIONS=$((REGRESSIONS + r))
done
[ "$REGRESSIONS" -eq 0 ] || fail "$REGRESSIONS GET mudaram depois do primeiro estado final"
ok "em cada laço, depois do primeiro estado final, nada muda"
[ "$ROW_UPDATED_A" = "$ROW_UPDATED_B" ] || fail "updated_at mudou depois do final: $ROW_UPDATED_A -> $ROW_UPDATED_B"
ok "updated_at estável no core-db ($ROW_UPDATED_A, igual 5 s depois)"
PENDING_WITH_HASH="$(awk '$3 == "PENDING" && $4 != "-"' "$WORK/race-all" | wc -l)"
HASH_DROPS="$(sort -n "$WORK/race-all" |
  awk '$3 == "PENDING" && $4 != "-" { had = 1 } had && $3 == "PENDING" && $4 == "-" { n++ } END { print n + 0 }')"
echo "      medida: $PENDING_WITH_HASH GET viram PENDING com hash;" \
  "$HASH_DROPS viram PENDING sem hash depois de algum PENDING com hash"
if wait_log_line "$BLOCKCHAIN_CONTAINER" "Task $SEND2_TASK: webhook CT15 entregue (CONFIRMED, HTTP 204)"; then
  ok "Blockchain Service: webhook CT15 entregue (CONFIRMED, HTTP 204), sem efeito se a consulta ativa gravou antes"
else
  echo "      aviso: o log do Blockchain Service não mostrou o webhook do envio 2"
fi
ok "envio 2 CONFIRMED: $ETHERSCAN/$SEND2_HASH"

# --- 8. Saldos finais e resumo ---------------------------------------------------
step "8: saldos finais"
ct6_balance_matches_ct7
FINAL_WEI="$(to_wei "$CT6_BALANCE")"
ct7_balance "$DEMO_WALLET_ADDRESS"
DEMO_FINAL="$CT7_BALANCE"
USER_GAS_WEI=$((FUND_WEI - 2 * SEND_WEI - FINAL_WEI))
echo "      usuário: $CT6_BALANCE ETH; gás dos 2 envios: $(from_wei "$USER_GAS_WEI") ETH"
if [ -n "$DEMO_BEFORE_WEI" ] && [ -n "$(to_wei "$DEMO_AFTER_FUND")" ]; then
  FUND_GAS_WEI=$((DEMO_BEFORE_WEI - $(to_wei "$DEMO_AFTER_FUND") - FUND_WEI))
  echo "      demonstração: $DEMO_BEFORE -> $DEMO_FINAL ETH; gás do abastecimento: $(from_wei "$FUND_GAS_WEI") ETH"
else
  echo "      demonstração: $DEMO_BEFORE -> $DEMO_FINAL ETH"
fi

# --- 9. Log do core-api ------------------------------------------------------------
step "9: nenhum segredo no log do core-api"
# A senha e o token vão ao grep por um descritor (-f), nunca pela linha de comando.
docker logs --since "$START_TS" "$CORE_API_CONTAINER" > "$WORK/core-api.log" 2>&1
LEAKS="$(grep -c -F -f <(printf '%s\n' "$PASSWORD" "$TOKEN" Bearer eyJ X-Service-Key jdbc:mysql SERVICE_KEY= @example.com) \
  "$WORK/core-api.log" || true)"
[ "$LEAKS" -eq 0 ] || fail "$LEAKS ocorrências de senha, token ou padrão proibido no log"
ok "senha, token, Bearer, eyJ, X-Service-Key, jdbc:mysql, SERVICE_KEY= e @example.com: 0 no log"

echo
echo "== Resumo"
echo "Usuário de teste: $EMAIL (userId $USER_ID, carteira $USER_ADDRESS)"
echo "Abastecimento ($FUND_ETH ETH, task $FUND_TASK): $ETHERSCAN/$FUND_HASH"
echo "Envio 1 ($SEND_ETH ETH, task $SEND1_TASK):       $ETHERSCAN/$SEND1_HASH"
echo "Envio 2 ($SEND_ETH ETH, task $SEND2_TASK):       $ETHERSCAN/$SEND2_HASH"
echo "Preço do gás inferido: $(gas_price_text "$GAS_PRICE_WEI"); GET do envio 2: $TOTAL_GETS"
echo "RESULTADO: todos os passos passaram."
