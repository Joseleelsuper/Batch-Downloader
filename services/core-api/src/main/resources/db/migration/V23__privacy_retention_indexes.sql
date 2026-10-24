-- Facilita las limpiezas frecuentes sin recorrer todas las filas de autenticación y mensajería.
CREATE INDEX ix_identity_tokens_expiry ON identity_tokens (expires_at);
CREATE INDEX ix_identity_tokens_consumed ON identity_tokens (consumed_at);
CREATE INDEX ix_core_inbox_processed ON core_inbox_messages (processed_at, message_id);
