-- Índice auxiliar de recetas e inventario.
CREATE INDEX IF NOT EXISTS ix_recetas_insumo ON recetas (id_insumo);

