#!/usr/bin/env bash
# Backup do Kixi no servidor: dump da base e ficheiros de configuração.
# Chamado como antes: backup.sh [daily|pre-update]. Mantém o dump simples em backups/ (14 diários, 10 pre-update) e, a mais:
#   - gera um pacote cifrado (AES-256) com o dump e a configuração, em backups/kixi-<tag>-<data>.tar.enc;
#   - com KIXI_BACKUP_DESTINO (destino rsync, ex.: utilizador@maquina:/caminho/) copia o pacote para fora do servidor;
#   - escreve backups/ULTIMO_OK, que verificar-backup.sh usa para alertar quando o backup falha ou fica velho.
set -euo pipefail

DIR=${KIXI_DIR:-/opt/kixi}
KEY=${KIXI_BACKUP_KEY:-/root/.kixi-backup.key}
cd "$DIR"
tag=${1:-daily}
agora=$(date +%Y%m%d-%H%M%S)
out=backups/kixi-$tag-$agora.sql.gz
FICHEIROS=(.env Caddyfile docker-compose.yml .manager-host)

log() { echo "$(date -Is) $*"; }
die() { log "ERRO: $*" >&2; exit 1; }

mkdir -p backups
chmod 700 backups

docker compose exec -T postgres pg_dump -U kixi -d kixi | gzip > "$out"
[[ -s $out ]] || { rm -f "$out"; die "backup vazio"; }
# Um dump truncado não é um dump: tem de abrir e conter as tabelas.
gzip -t "$out" || { rm -f "$out"; die "dump corrompido"; }
# grep -c lê tudo: com grep -q o pipefail trataria o SIGPIPE como falha.
(( $(zcat "$out" | grep -c '^CREATE TABLE public.accounts ') >= 1 )) || { rm -f "$out"; die "o dump não tem a tabela accounts"; }
chmod 600 "$out"
log "dump: $out ($(du -h "$out" | cut -f1))"

if [[ -s $KEY ]]; then
  trabalho=$(mktemp -d backups/.trabalho.XXXXXX)
  trap 'rm -rf "$trabalho"' EXIT
  cp "$out" "$trabalho/kixi.sql.gz"
  existentes=()
  for f in "${FICHEIROS[@]}"; do [[ -e $f ]] && existentes+=("$f"); done
  tar -czf "$trabalho/config.tar.gz" "${existentes[@]}"
  alvo=backups/kixi-$tag-$agora.tar.enc
  ( cd "$trabalho" && sha256sum ./*.gz > SHA256SUMS && tar -cf - . ) \
    | openssl enc -aes-256-cbc -pbkdf2 -iter 200000 -salt -pass "file:$KEY" -out "$alvo"
  chmod 600 "$alvo"
  [[ -s $alvo ]] || die "pacote cifrado vazio"
  log "pacote cifrado: $alvo ($(du -h "$alvo" | cut -f1))"
  if [[ -n ${KIXI_BACKUP_DESTINO:-} ]]; then
    rsync -a --partial "$alvo" "$KIXI_BACKUP_DESTINO" || die "a cópia para fora do servidor falhou"
    log "copiado para $KIXI_BACKUP_DESTINO"
    date -Is > backups/ULTIMO_FORA
  else
    log "AVISO: sem KIXI_BACKUP_DESTINO, o backup só existe neste servidor"
  fi
else
  log "AVISO: sem chave de cifra em $KEY, não se gerou o pacote cifrado nem se copiou para fora"
fi

for t in daily pre-update; do
  keep=14; [[ $t == pre-update ]] && keep=10
  for ext in sql.gz tar.enc; do
    { ls -1t backups/kixi-$t-*.$ext 2>/dev/null || true; } | tail -n +$((keep+1)) | xargs -r rm -f
  done
done

date -Is > backups/ULTIMO_OK
log "backup concluído"
