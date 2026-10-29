#!/usr/bin/env bash
# Prova que o backup restaura: decifra o pacote mais recente (ou o indicado), confere as somas, carrega o dump numa base
# vazia descartável e confirma que as tabelas e as contas lá estão. Não toca na base de produção.
# Uso: testar-restauro.sh [pacote.tar.enc]     (mensalmente, ver README.md)
set -euo pipefail

DIR=${KIXI_DIR:-/opt/kixi}
KEY=${KIXI_BACKUP_KEY:-/root/.kixi-backup.key}
pacote=${1:-$(ls -1t "$DIR"/backups/kixi-*.tar.enc 2>/dev/null | head -1)}
[[ -n $pacote && -s $pacote ]] || { echo "sem pacote cifrado para testar" >&2; exit 1; }
[[ -s $KEY ]] || { echo "chave em falta: $KEY" >&2; exit 1; }

trabalho=$(mktemp -d)
base=kixi-restauro-teste-$$
cleanup() { docker rm -f "$base" >/dev/null 2>&1 || true; rm -rf "$trabalho"; }
trap cleanup EXIT

openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -pass "file:$KEY" -in "$pacote" | tar -xf - -C "$trabalho"
( cd "$trabalho" && sha256sum -c SHA256SUMS )

docker run -d --name "$base" -e POSTGRES_PASSWORD=teste -e POSTGRES_USER=kixi -e POSTGRES_DB=kixi postgres:16-alpine >/dev/null
# A imagem arranca um servidor provisório, termina-o e arranca o definitivo: só vale depois de "init process complete".
for _ in $(seq 1 60); do
  docker logs "$base" 2>&1 | grep -q 'init process complete' && docker exec "$base" pg_isready -U kixi -d kixi >/dev/null 2>&1 && break
  sleep 1
done
zcat "$trabalho/kixi.sql.gz" | docker exec -i "$base" psql -q -v ON_ERROR_STOP=1 -U kixi -d kixi >/dev/null

q() { docker exec "$base" psql -U kixi -d kixi -Atc "$1"; }
tabelas=$(q "select count(*) from information_schema.tables where table_schema='public'")
contas=$(q "select count(*) from accounts")
migracoes=$(q "select count(*) from flyway_schema_history where success")
(( tabelas > 20 )) || { echo "restauro suspeito: só $tabelas tabelas" >&2; exit 1; }
(( contas > 0 )) || { echo "restauro suspeito: sem contas" >&2; exit 1; }
(( $(tar -tzf "$trabalho/config.tar.gz" | grep -c '^docker-compose.yml$') == 1 )) || { echo "a configuração não vem no pacote" >&2; exit 1; }
echo "restauro OK: $tabelas tabelas, $contas contas, $migracoes migrações, configuração presente ($(basename "$pacote"))"
date -Is > "$DIR/backups/ULTIMO_RESTAURO_OK"
