-- Elimina cualquier aviso de descarga que aún estuviera pendiente en el outbox antes de retirar
-- el productor y consumidor de esos mensajes.
DELETE FROM core_outbox_events
WHERE published_at IS NULL
  AND event_type = 'notification.email.requested'
  AND JSON_UNQUOTE(JSON_EXTRACT(payload, '$.payload.template'))
      IN ('DOWNLOAD_READY', 'DOWNLOAD_FAILED');

-- La preferencia y la marca por trabajo dejaron de tener consumidores cuando desaparecieron los
-- avisos por correo del ZIP.
ALTER TABLE core_users
    DROP COLUMN notify_on_job_completion;

ALTER TABLE download_jobs
    DROP COLUMN notify_when_ready;
