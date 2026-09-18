CREATE TABLE portability_request (
    id VARCHAR(64) PRIMARY KEY,
    msisdn VARCHAR(20) NOT NULL,
    documento_titular VARCHAR(50) NOT NULL,
    operador_donante VARCHAR(50) NOT NULL,
    operador_receptor VARCHAR(50) NOT NULL,
    estado VARCHAR(30) NOT NULL,
    pin VARCHAR(20),
    pin_expiracion TIMESTAMP WITH TIME ZONE,
    intentos_confirmacion INTEGER NOT NULL DEFAULT 0,
    fecha_creacion TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    fecha_pin_generado TIMESTAMP WITH TIME ZONE,
    fecha_pin_confirmado TIMESTAMP WITH TIME ZONE,
    fecha_completada TIMESTAMP WITH TIME ZONE,
    motivo_rechazo TEXT
);

CREATE TABLE ported_number (
    msisdn VARCHAR(20) PRIMARY KEY,
    operador_anterior VARCHAR(50) NOT NULL,
    operador_actual VARCHAR(50) NOT NULL,
    fecha_portacion TIMESTAMP WITH TIME ZONE NOT NULL
);
-- Aplicar también a volúmenes existentes. No modifica las tablas de negocio.
CREATE TABLE IF NOT EXISTS processed_message (
    operation VARCHAR(40) NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    reply TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (operation, request_id)
);
