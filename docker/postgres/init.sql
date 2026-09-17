CREATE TABLE portability_request (
    id VARCHAR(64) PRIMARY KEY,
    msisdn VARCHAR(20) NOT NULL,
    documento_titular VARCHAR(50) NOT NULL,
    operador_donante VARCHAR(50) NOT NULL,
    operador_receptor VARCHAR(50) NOT NULL,
    estado VARCHAR(30) NOT NULL,
    pin VARCHAR(20),
    pin_expiracion TIMESTAMPTZ,
    intentos_confirmacion INTEGER NOT NULL DEFAULT 0,
    fecha_creacion TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    fecha_pin_generado TIMESTAMPTZ,
    fecha_pin_confirmado TIMESTAMPTZ,
    fecha_completada TIMESTAMPTZ,
    motivo_rechazo TEXT
);

CREATE TABLE ported_number (
    msisdn VARCHAR(20) PRIMARY KEY,
    operador_anterior VARCHAR(50) NOT NULL,
    operador_actual VARCHAR(50) NOT NULL,
    fecha_portacion TIMESTAMPTZ NOT NULL
);
