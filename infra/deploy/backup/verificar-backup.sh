#!/usr/bin/env bash
# Alerta se o backup falhou, ficou velho ou nunca foi copiado para fora. Corre de hora a hora (ver README.md).
# Sai com 1 e, com KIXI_ALERTA_WEBHOOK (URL que aceita POST com {"text": "..."}), avisa. Idade máxima: KIXI_BACKUP_MAX_HORAS (26).
set -uo pipefail

DIR=${KIXI_DIR:-/opt/kixi}
MAX=${KIXI_BACKUP_MAX_HORAS:-26}
problemas=()

idade_horas() { echo $(( ( $(date +%s) - $(date -d "$(cat "$1")" +%s) ) / 3600 )); }

if [[ ! -s $DIR/backups/ULTIMO_OK ]]; then
  problemas+=("o backup nunca terminou com sucesso")
elif (( $(idade_horas "$DIR/backups/ULTIMO_OK") > MAX )); then
  problemas+=("o último backup bem-sucedido tem mais de ${MAX} h")
fi
ultimo=$(ls -1t "$DIR"/backups/kixi-*.sql.gz 2>/dev/null | head -1)
if [[ -z $ultimo ]] || (( $(stat -c %s "$ultimo") < 1024 )); then
  problemas+=("o dump mais recente falta ou é suspeito de vazio")
fi
if [[ -n ${KIXI_BACKUP_DESTINO:-} ]]; then
  if [[ ! -s $DIR/backups/ULTIMO_FORA ]] || (( $(idade_horas "$DIR/backups/ULTIMO_FORA") > MAX )); then
    problemas+=("a cópia para fora do servidor está atrasada")
  fi
fi

if (( ${#problemas[@]} )); then
  msg="Kixi, backup: $(IFS='; '; echo "${problemas[*]}")"
  echo "$msg" >&2
  [[ -n ${KIXI_ALERTA_WEBHOOK:-} ]] && curl -fsS -m 10 -X POST -H 'Content-Type: application/json' \
    -d "{\"text\": \"$msg\"}" "$KIXI_ALERTA_WEBHOOK" >/dev/null || true
  exit 1
fi
echo "ok: $(date -Is)"
