-- Aplicar también a volúmenes existentes. No modifica las tablas de negocio.
CREATE TABLE IF NOT EXISTS processed_message (
    operation VARCHAR(40) NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    reply TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (operation, request_id)
);
