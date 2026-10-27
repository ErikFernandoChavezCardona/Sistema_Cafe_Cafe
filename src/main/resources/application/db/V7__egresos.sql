-- Quién registró cada egreso (los anteriores quedan sin usuario) y un índice para filtrar por fecha
ALTER TABLE egresos ADD COLUMN id_usuario INTEGER REFERENCES usuarios (id_usuario);
CREATE INDEX ix_egresos_fecha ON egresos (creado_en);
