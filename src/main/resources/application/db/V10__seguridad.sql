-- Seguridad: bloqueo por intentos, cambio obligatorio de clave, código de recuperación y bitácora de auditoría.
ALTER TABLE usuarios ADD COLUMN debe_cambiar_clave INTEGER NOT NULL DEFAULT 0 CHECK (debe_cambiar_clave IN (0, 1));
ALTER TABLE usuarios ADD COLUMN intentos_fallidos INTEGER NOT NULL DEFAULT 0;
ALTER TABLE usuarios ADD COLUMN bloqueado_hasta TEXT;
ALTER TABLE usuarios ADD COLUMN ultimo_acceso TEXT;
ALTER TABLE usuarios ADD COLUMN recuperacion_hash TEXT;

CREATE TABLE auditoria (
    id_auditoria INTEGER PRIMARY KEY AUTOINCREMENT,
    fecha        TEXT NOT NULL DEFAULT (datetime('now', 'localtime')),
    id_usuario   INTEGER REFERENCES usuarios(id_usuario),
    usuario      TEXT NOT NULL DEFAULT '',
    accion       TEXT NOT NULL,
    detalle      TEXT NOT NULL DEFAULT ''
);
CREATE INDEX ix_auditoria_fecha ON auditoria (fecha);
CREATE INDEX ix_auditoria_accion ON auditoria (accion);

INSERT OR IGNORE INTO config (clave, valor) VALUES ('respaldo_externo', '');
INSERT OR IGNORE INTO config (clave, valor) VALUES ('respaldo_ultimo_local', '');
INSERT OR IGNORE INTO config (clave, valor) VALUES ('respaldo_ultimo_externo', '');
