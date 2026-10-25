-- Quién cerró la caja (quién la abrió ya se guarda en id_usuario)
ALTER TABLE turnos_caja ADD COLUMN id_usuario_cierre INTEGER REFERENCES usuarios (id_usuario);
