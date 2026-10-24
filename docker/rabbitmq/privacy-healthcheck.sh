#!/bin/sh
set -eu

rabbitmq-diagnostics -q check_running
rabbitmq-diagnostics -q check_local_alarms

# Una política puede cambiar colas existentes sin redeclararlas con argumentos incompatibles.
# Aplicar después del arranque conserva el bootstrap del usuario y vhost de Docker.
if [ ! -f /tmp/batch-downloader-privacy-policies ]; then
    rabbitmqctl -q set_policy --priority 10 --apply-to queues access-mail-retention \
        '^notification\.email-requests\.v1$' '{"message-ttl":900000}'
    rabbitmqctl -q set_policy --priority 10 --apply-to queues dead-letter-retention \
        '\.dlq(\.|$)' '{"message-ttl":86280000}'
    rabbitmqctl -q set_policy --priority 20 --apply-to queues access-mail-dead-letter-retention \
        '^notification\.email-requests\.dlq\.v1$' '{"message-ttl":60000}'
    touch /tmp/batch-downloader-privacy-policies
fi
