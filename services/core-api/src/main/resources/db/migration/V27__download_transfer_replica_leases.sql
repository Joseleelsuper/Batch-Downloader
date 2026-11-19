-- Cada réplica renueva solo sus transferencias. Arrancar otra no invalida sockets vivos.
ALTER TABLE download_job_transfers
    ADD COLUMN core_instance_id CHAR(36) NOT NULL DEFAULT 'legacy',
    ADD COLUMN last_seen_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    ADD KEY ix_download_transfer_lease (last_seen_at);
