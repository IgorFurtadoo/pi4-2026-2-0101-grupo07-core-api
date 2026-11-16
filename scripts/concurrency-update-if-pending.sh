#!/usr/bin/env bash
# Prova do UPDATE condicional (seção 8.4 do desenho; seção 11, item 5) contra o
# MySQL real, sem ETH: linhas PENDING sintéticas em eth_transaction recebem, ao
# mesmo tempo, notificações contraditórias do webhook (CT15) e consultas do
# Frontend (CT13). Cada linha tem de terminar em UM estado final, igual a uma das
# notificações enviadas (sem mistura de campos), que depois nunca muda.
#
# Uso: scripts/concurrency-update-if-pending.sh
#   (com o core-api e o core-db no ar: docker compose up -d)
#
# Variáveis (todas opcionais):
#   CORE_API_URL        base do Core API vista do host (padrão http://127.0.0.1:8080)
#   ROWS                linhas por rodada (padrão 10)
#   ROUNDS              rodadas (padrão 5)
#   CORE_API_CONTAINER  padrão core-api-core-api-1
#   CORE_DB_CONTAINER   padrão core-api-core-db-1
#
# Por linha, na rajada 1: 16 notificações distintas largadas juntas (8 CONFIRMED,
# cada uma com um hash aleatório; 5 FAILED com hash; 3 FAILED sem hash, uma por
# motivo) e 6 GET /transactions/{taskId}. Na rajada 2, depois do estado final: 8
# notificações com o estado oposto ao vencedor e 4 GET.
#
# Onde roda: no host. O SQL vai por docker exec no core-db (a porta do MySQL não é
# publicada), com a senha lida do ambiente do próprio container. As notificações
# saem de dentro do container core-api, com a SERVICE_KEY do ambiente dele, passada
# ao curl pela entrada padrão: a chave nunca sai do container nem aparece na lista
# de processos. A senha e o token do usuário de teste ficam só em variáveis do
# shell e vão ao curl pela entrada padrão. Não use "bash -x" aqui.
#
# Não gasta ETH. Cria um usuário c9-teste-<aleatório>@example.com (o cadastro cria
# uma carteira vazia no Blockchain Service) e ROWS x ROUNDS linhas sintéticas,
# que ficam no banco.
set -euo pipefail

CORE_API_URL="${CORE_API_URL:-http://127.0.0.1:8080}"
ROWS="${ROWS:-10}"
ROUNDS="${ROUNDS:-5}"
CORE_API_CONTAINER="${CORE_API_CONTAINER:-core-api-core-api-1}"
CORE_DB_CONTAINER="${CORE_DB_CONTAINER:-core-api-core-db-1}"

# Destino e valor das linhas sintéticas (não há envio de verdade).
SYNTHETIC_DESTINATION="0x0000000000000000000000000000000000000001"
SYNTHETIC_AMOUNT="0.0001"
REASONS=(REJECTED_BY_NETWORK TRANSACTION_REVERTED INTERNAL_ERROR)

if ! [[ "$ROWS" =~ ^[1-9][0-9]?$ && "$ROUNDS" =~ ^[1-9][0-9]?$ ]]; then
  echo "ROWS e ROUNDS: inteiros de 1 a 99." >&2
  exit 2
fi

WORK="$(mktemp -d)"
cleanup() {
  jobs -p | xargs -r kill 2>/dev/null || true
  rm -rf "$WORK"
}
trap cleanup EXIT

START_TS="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
FAILURES=0

check() { # check <condição verdadeira?> <rótulo>
  if [ "$1" = "1" ]; then
    echo "OK    $2"
  else
    echo "FALHA $2"
    FAILURES=$((FAILURES + 1))
  fi
}

random_hex() { # random_hex <bytes>
  od -An -tx1 -N"$1" /dev/urandom | tr -d ' \n'
}

# sql <consulta>: roda no core-db; saída sem cabeçalho, separada por tabulação.
# Os valores das consultas são gerados por este script ou validados por regex.
sql() {
  docker exec "$CORE_DB_CONTAINER" sh -c \
    'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u "$MYSQL_USER" core -N -B -e "$1"' _ "$1"
}

# ---------------------------------------------------------------------------
echo "== Usuário de teste"
EMAIL="c9-teste-$(random_hex 4)@example.com"
PASSWORD="$(head -c 18 /dev/urandom | base64 | tr -d '/+=')"
BODY="$(printf '{"email":"%s","password":"%s"}' "$EMAIL" "$PASSWORD" |
  curl -s --max-time 30 -H 'Content-Type: application/json' --data-binary @- "$CORE_API_URL/users")"
if ! [[ "$BODY" =~ \"userId\":([0-9]+) ]]; then
  echo "FALHA cadastro: $BODY" >&2
  exit 1
fi
USER_ID="${BASH_REMATCH[1]}"
echo "Cadastrado: $EMAIL (userId $USER_ID)"

BODY="$(printf '{"email":"%s","password":"%s"}' "$EMAIL" "$PASSWORD" |
  curl -s --max-time 30 -H 'Content-Type: application/json' --data-binary @- "$CORE_API_URL/auth/login")"
if ! [[ "$BODY" =~ \"token\":\"([^\"]+)\" ]]; then
  echo "FALHA login (resposta sem token)" >&2
  exit 1
fi
TOKEN="${BASH_REMATCH[1]}"
echo "Login OK (token guardado só em variável)"

# ---------------------------------------------------------------------------
# Disparo dentro do container core-api. Lê da entrada padrão linhas
# "<taskId> <idx> <status> <hash ou -> <motivo ou ->", prepara um processo por
# notificação e larga todos juntos: cada um espera na FIFO, que só abre (EOF)
# quando o escritor fecha. Imprime "<taskId> <idx> <início ns> <fim ns> <código>".
# shellcheck disable=SC2016
WEBHOOK_BURST='
set -u
fifo="$(mktemp -u /tmp/c9-go.XXXXXX)"
mkfifo "$fifo"
now="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
while read -r task idx status hash reason; do
  body="{\"taskId\":\"$task\",\"status\":\"$status\""
  [ "$hash" != "-" ] && body="$body,\"transactionHash\":\"$hash\""
  [ "$reason" != "-" ] && body="$body,\"failureReason\":\"$reason\""
  body="$body,\"updatedAt\":\"$now\"}"
  (
    read -r _ < "$fifo" || true
    s="$(date +%s%N)"
    code="$(printf "X-Service-Key: %s\n" "$SERVICE_KEY" | curl -s -o /dev/null --max-time 20 \
      -w "%{http_code}" -H @- -H "Content-Type: application/json" --data-binary "$body" \
      http://127.0.0.1:8080/webhooks/tasks)" || code=000
    e="$(date +%s%N)"
    echo "$task $idx $s $e $code"
  ) &
done
sleep 1
exec 3>"$fifo"
sleep 0.2
exec 3>&-
wait
rm -f "$fifo"
'

# GET /transactions/{taskId} do host, largados juntos por uma FIFO do host.
# Entrada: linhas "<taskId> <idx>". Saída em $1:
# "<taskId> <idx> <início ns> <fim ns> <código> <status> <hash ou -> <motivo ou -> <updatedAt ou ->"
get_burst() {
  local out="$1" fifo="$WORK/get-go" task idx
  rm -f "$fifo"
  mkfifo "$fifo"
  while read -r task idx; do
    (
      read -r _ < "$fifo" || true
      local s e code body status hash reason updated
      s="$(date +%s%N)"
      code="$(printf 'Authorization: Bearer %s\n' "$TOKEN" | curl -s -o "$WORK/get-$task-$idx" \
        --max-time 20 -w '%{http_code}' -H @- "$CORE_API_URL/transactions/$task")" || code=000
      e="$(date +%s%N)"
      body="$(cat "$WORK/get-$task-$idx" 2>/dev/null || true)"
      status=-; hash=-; reason=-; updated=-
      [[ "$body" =~ \"status\":\"([A-Z_]+)\" ]] && status="${BASH_REMATCH[1]}"
      [[ "$body" =~ \"transactionHash\":\"(0x[0-9a-fA-F]{64})\" ]] && hash="${BASH_REMATCH[1]}"
      [[ "$body" =~ \"failureReason\":\"([A-Z_]+)\" ]] && reason="${BASH_REMATCH[1]}"
      [[ "$body" =~ \"updatedAt\":\"([^\"]+)\" ]] && updated="${BASH_REMATCH[1]}"
      echo "$task $idx $s $e $code $status $hash $reason $updated" >> "$out"
    ) &
  done
  # Mesmo tempo de preparo do disparo no container (docker exec + 1 s + 0,2 s).
  sleep 1.3
  exec 3>"$fifo"
  sleep 0.2
  exec 3>&-
  wait
}

# Rajada: webhooks (no container) e GETs (no host) ao mesmo tempo.
# burst <spec dos webhooks> <lista de GETs> <saída webhooks> <saída GETs>
burst() {
  # O wait sem argumento do get_burst espera também este docker exec.
  docker exec -i "$CORE_API_CONTAINER" bash -c "$WEBHOOK_BURST" < "$1" > "$3" &
  get_burst "$4" < "$2"
}

# Fotografia: "<taskId> <status> <hash ou -> <motivo ou -> <updated_at com ms>".
snapshot() { # snapshot <arquivo com taskIds> <saída>
  local ids
  ids="$(awk '{printf "%s'\''%s'\''", (NR > 1 ? "," : ""), $1}' "$1")"
  sql "SELECT task_id, status, IFNULL(transaction_hash,'-'), IFNULL(failure_reason,'-'),
              DATE_FORMAT(updated_at, '%Y-%m-%dT%H:%i:%s.%f')
         FROM eth_transaction WHERE task_id IN ($ids) ORDER BY task_id" | tr '\t' ' ' > "$2"
}

ALL_IDS="$WORK/all-ids"
: > "$ALL_IDS"
WEBHOOKS_SENT=0
GETS_SENT=0

for round in $(seq 1 "$ROUNDS"); do
  echo "== Rodada $round de $ROUNDS"
  R="$WORK/r$round"
  mkdir "$R"
  : > "$R/ids"; : > "$R/spec1"; : > "$R/gets1"; : > "$R/spec2"; : > "$R/gets2"

  # Linhas PENDING sintéticas do usuário de teste.
  values=""
  for _ in $(seq 1 "$ROWS"); do
    task="$(cat /proc/sys/kernel/random/uuid)"
    echo "$task" >> "$R/ids"
    values="$values${values:+,}('$task', $USER_ID, '$SYNTHETIC_DESTINATION', $SYNTHETIC_AMOUNT,
      'PENDING', NULL, NULL, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))"
  done
  sql "INSERT INTO eth_transaction (task_id, user_id, destination_address, amount_eth, status,
         transaction_hash, failure_reason, created_at, updated_at) VALUES $values"
  cat "$R/ids" >> "$ALL_IDS"

  # Rajada 1: 16 notificações distintas por linha, em ordem embaralhada, e 6 GET.
  while read -r task; do
    for i in 1 2 3 4 5 6 7 8; do
      echo "$task c$i CONFIRMED 0x$(random_hex 32) -"
    done
    for i in 1 2 3 4 5; do
      echo "$task f$i FAILED 0x$(random_hex 32) ${REASONS[$((i % 3))]}"
    done
    for i in 0 1 2; do
      echo "$task n$i FAILED - ${REASONS[$i]}"
    done
    for i in 1 2 3 4 5 6; do
      echo "$task g$i" >> "$R/gets1"
    done
  done < "$R/ids" | awk 'BEGIN { srand() } { print rand() "\t" $0 }' | sort -n | cut -f2- > "$R/spec1"

  burst "$R/spec1" "$R/gets1" "$R/hooks1.out" "$R/gets1.out"
  snapshot "$R/ids" "$R/snap1"

  # Rajada 2: 8 notificações com o estado oposto ao vencedor, e 4 GET.
  while read -r task status _ _ _; do
    for i in 1 2 3 4 5 6 7 8; do
      if [ "$status" = "CONFIRMED" ]; then
        if [ $((i % 2)) -eq 0 ]; then
          echo "$task x$i FAILED 0x$(random_hex 32) ${REASONS[$((i % 3))]}"
        else
          echo "$task x$i FAILED - ${REASONS[$((i % 3))]}"
        fi
      else
        echo "$task x$i CONFIRMED 0x$(random_hex 32) -"
      fi
    done
    for i in 1 2 3 4; do
      echo "$task h$i" >> "$R/gets2"
    done
  done < "$R/snap1" > "$R/spec2"

  burst "$R/spec2" "$R/gets2" "$R/hooks2.out" "$R/gets2.out"
  snapshot "$R/ids" "$R/snap2"

  WEBHOOKS_SENT=$((WEBHOOKS_SENT + $(wc -l < "$R/spec1") + $(wc -l < "$R/spec2")))
  GETS_SENT=$((GETS_SENT + $(wc -l < "$R/gets1") + $(wc -l < "$R/gets2")))

  # --- Conferências da rodada ---
  n_rows="$(wc -l < "$R/ids")"
  check "$([ "$(wc -l < "$R/snap1")" -eq "$n_rows" ] && echo 1)" "rodada $round: $n_rows linhas no banco"

  bad="$(cat "$R/hooks1.out" "$R/hooks2.out" | awk '$5 != 204' | wc -l)"
  total="$(cat "$R/hooks1.out" "$R/hooks2.out" | wc -l)"
  check "$([ "$bad" -eq 0 ] && [ "$total" -eq "$(cat "$R/spec1" "$R/spec2" | wc -l)" ] && echo 1)" \
    "rodada $round: $total notificações, todas 204 ($bad fora disso)"

  bad="$(cat "$R/gets1.out" "$R/gets2.out" | awk '$5 != 200' | wc -l)"
  total="$(cat "$R/gets1.out" "$R/gets2.out" | wc -l)"
  check "$([ "$bad" -eq 0 ] && echo 1)" "rodada $round: $total GET, todos 200 ($bad fora disso)"

  pending="$(awk '$2 == "PENDING"' "$R/snap1" | wc -l)"
  check "$([ "$pending" -eq 0 ] && echo 1)" "rodada $round: nenhuma linha PENDING depois da rajada 1"

  # A tupla final (status, hash, motivo) é exatamente uma das notificações da rajada 1.
  mixed="$(awk 'NR == FNR { sent[$1 " " $3 " " $4 " " $5] = 1; next }
                !(($1 " " $2 " " $3 " " $4) in sent) { print }' "$R/spec1" "$R/snap1" | wc -l)"
  check "$([ "$mixed" -eq 0 ] && echo 1)" \
    "rodada $round: estado final de cada linha é uma notificação enviada, sem mistura ($mixed fora disso)"

  check "$(cmp -s "$R/snap1" "$R/snap2" && echo 1)" \
    "rodada $round: rajada 2 não mudou status, hash, motivo nem updated_at"

  # GET que viu estado final viu o vencedor (e o updatedAt dele, em segundos).
  wrong="$(awk 'NR == FNR { st[$1] = $2; h[$1] = $3; r[$1] = $4; u[$1] = substr($5, 1, 19) "Z"; next }
                $6 != "PENDING" && ($6 != st[$1] || $7 != h[$1] || $8 != r[$1] || $9 != u[$1]) { print }' \
           "$R/snap2" "$R/gets1.out" "$R/gets2.out" | wc -l)"
  check "$([ "$wrong" -eq 0 ] && echo 1)" "rodada $round: todo GET com estado final mostra o gravado ($wrong fora disso)"

  late="$(awk '$6 == "PENDING"' "$R/gets2.out" | wc -l)"
  check "$([ "$late" -eq 0 ] && echo 1)" "rodada $round: nenhum GET da rajada 2 viu PENDING"

  # Sobreposição: para cada linha, o maior número de notificações em voo no mesmo instante.
  awk '{ n[$1]++; s[$1, n[$1]] = $3; e[$1, n[$1]] = $4 }
       END {
         for (t in n) {
           best = 0
           for (i = 1; i <= n[t]; i++) {
             c = 0
             for (j = 1; j <= n[t]; j++) if (s[t, j] <= s[t, i] && e[t, j] >= s[t, i]) c++
             if (c > best) best = c
           }
           print best
         }
       }' "$R/hooks1.out" > "$R/overlap"
  awk '{ if (min == "" || $3 < min) min = $3; if ($3 > max) max = $3 }
       END { printf "%.1f\n", (max - min) / 1e6 }' "$R/hooks1.out" > "$R/spread"
  echo "      rajada 1: em voo ao mesmo tempo por linha (de 16): mín $(sort -n "$R/overlap" | head -1)," \
    "máx $(sort -n "$R/overlap" | tail -1); largada espalhada em $(cat "$R/spread") ms"
  echo "      vencedores: $(awk '{ print $2 }' "$R/snap1" | sort | uniq -c | awk '{ printf "%s %s  ", $1, $2 }')"
done

# ---------------------------------------------------------------------------
echo "== Conferências finais"
TOTAL_ROWS="$(wc -l < "$ALL_IDS")"

# Cada notificação perdedora (tupla diferente da gravada) vai ao log em WARN
# depois de o UPDATE condicional achar 0 linhas: 15 da rajada 1 e 8 da rajada 2.
LOG="$WORK/core-api.log"
docker logs --since "$START_TS" "$CORE_API_CONTAINER" > "$LOG" 2>&1
warns="$(grep -F 'notificação contradiz o estado final' "$LOG" | grep -F -f "$ALL_IDS" | wc -l || true)"
expected=$((TOTAL_ROWS * 23))
check "$([ "$warns" -eq "$expected" ] && echo 1)" \
  "log: $warns avisos de notificação contraditória (esperado $expected = $TOTAL_ROWS linhas x 23)"

errors="$(grep -c -E 'Erro inesperado|falha no banco|falha ao consultar o banco|ao gravar' "$LOG" || true)"
check "$([ "$errors" -eq 0 ] && echo 1)" "log: nenhum erro inesperado nem falha de banco ($errors)"

# A senha e o token vão ao grep por um descritor (-f), nunca pela linha de comando.
leaks="$(grep -c -F -f <(printf '%s\n' "$PASSWORD" "$TOKEN" Bearer eyJ X-Service-Key jdbc:mysql SERVICE_KEY= @example.com) \
  "$LOG" || true)"
check "$([ "$leaks" -eq 0 ] && echo 1)" \
  "log: senha, token, Bearer, eyJ, X-Service-Key, jdbc:mysql, SERVICE_KEY= e @example.com ausentes ($leaks)"

echo
echo "Resumo: $TOTAL_ROWS linhas em $ROUNDS rodadas, $WEBHOOKS_SENT notificações e $GETS_SENT GET."
echo "Vencedores: $(cat "$WORK"/r*/snap2 | awk '{ print $2 }' | sort | uniq -c | awk '{ printf "%s %s  ", $1, $2 }')"
echo "Em voo ao mesmo tempo por linha (rajada 1, de 16): $(cat "$WORK"/r*/overlap | sort -n |
  awk '{ v[NR] = $1; t += $1 } END { printf "mín %d, mediana %d, máx %d, média %.1f", v[1], v[int((NR + 1) / 2)], v[NR], t / NR }')"
echo "Usuário de teste: $EMAIL (userId $USER_ID); linhas sintéticas ficam no banco."
if [ "$FAILURES" -gt 0 ]; then
  echo "RESULTADO: $FAILURES conferência(s) falharam." >&2
  exit 1
fi
echo "RESULTADO: todas as conferências passaram."
