# Backup do Kixi

`backup.sh` substitui `/opt/kixi/backup.sh` (mesmos argumentos: `daily`, `pre-update`; chamado por `kixi-backup.service` às 03:30 e antes de cada atualização). Faz o que o anterior fazia e acrescenta:

| | |
|---|---|
| Valida o dump | abre (`gzip -t`) e contém `accounts`; senão falha e não deixa um ficheiro vazio |
| Pacote cifrado | `kixi-<tag>-<data>.tar.enc` (AES-256, `openssl enc -pbkdf2`) com o dump e `.env`, `Caddyfile`, `docker-compose.yml`, `.manager-host` |
| Fora do servidor | `rsync` do pacote para `KIXI_BACKUP_DESTINO`, se definido |
| Retenção | 14 diários e 10 pre-update (dumps e pacotes) |
| Estado | `backups/ULTIMO_OK` e `backups/ULTIMO_FORA`, que `verificar-backup.sh` lê |

## Instalar (uma vez, no servidor)

1. Chave de cifra, só no servidor: `umask 077; openssl rand -base64 48 > /root/.kixi-backup.key`.
   **Copiar a chave para um cofre fora do servidor.** Sem ela os pacotes não abrem, e se ela só existir no servidor, perde-se com ele.
2. `cp /opt/kixi/backup.sh /opt/kixi/backup.sh.antigo && install -m 750 backup.sh /opt/kixi/backup.sh`.
3. Destino externo: definir `KIXI_BACKUP_DESTINO` (por exemplo `utilizador@maquina:/caminho/`, com chave SSH sem palavra-passe) no ambiente do `kixi-backup.service` (`systemctl edit kixi-backup.service` → `[Service]` `Environment=KIXI_BACKUP_DESTINO=...`). Sem isto o backup continua só neste servidor e o script avisa-o em cada execução.
4. Alerta: `install -m 750 verificar-backup.sh /opt/kixi/` e correr de hora a hora (timer `systemd` ou `cron`), com `KIXI_ALERTA_WEBHOOK` se houver onde avisar. Falha se o último backup tem mais de 26 h, o dump é suspeito de vazio ou a cópia para fora está atrasada.

## Testar o restauro (mensalmente)

```
/opt/kixi/testar-restauro.sh            # o pacote mais recente
/opt/kixi/testar-restauro.sh /caminho/kixi-daily-AAAAMMDD-HHMMSS.tar.enc
```

Decifra, confere `SHA256SUMS`, carrega o dump numa base vazia descartável (`postgres:16-alpine`), confirma tabelas, contas e a configuração, e apaga tudo. Não toca na base de produção. Grava `backups/ULTIMO_RESTAURO_OK`.

Restauro a sério (desastre): decifrar com `openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -pass file:CHAVE -in PACOTE | tar -xf -`, recriar a base vazia e `zcat kixi.sql.gz | docker compose exec -T postgres psql -U kixi -d kixi`; a configuração está em `config.tar.gz`.

## Verificado

Em 28/10/2026, no servidor de produção: backup com pacote cifrado e restauro numa base vazia (52 tabelas, 67 contas, 61 migrações, configuração presente).
