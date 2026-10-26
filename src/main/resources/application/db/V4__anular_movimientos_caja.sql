-- Anular en vez de borrar: el movimiento queda en el historial pero ya no suma ni resta al efectivo
ALTER TABLE egresos ADD COLUMN anulado INTEGER NOT NULL DEFAULT 0 CHECK (anulado IN (0, 1));
ALTER TABLE egresos ADD COLUMN motivo_anulacion TEXT;
ALTER TABLE egresos ADD COLUMN id_usuario_anulo INTEGER REFERENCES usuarios (id_usuario);
ALTER TABLE egresos ADD COLUMN anulado_en TEXT;
ALTER TABLE ingresos_caja ADD COLUMN anulado INTEGER NOT NULL DEFAULT 0 CHECK (anulado IN (0, 1));
ALTER TABLE ingresos_caja ADD COLUMN motivo_anulacion TEXT;
ALTER TABLE ingresos_caja ADD COLUMN id_usuario_anulo INTEGER REFERENCES usuarios (id_usuario);
ALTER TABLE ingresos_caja ADD COLUMN anulado_en TEXT;
