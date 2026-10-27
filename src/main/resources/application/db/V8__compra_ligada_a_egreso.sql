-- Une el egreso de caja con la compra de inventario que lo originó: así, al anular el egreso, se puede revertir también la compra
ALTER TABLE egresos ADD COLUMN id_movimiento_inventario INTEGER REFERENCES movimientos_inventario (id_movimiento);
-- Costo que tenía el insumo ANTES de una compra que lo actualizó (queda vacío si esa compra no tocó el costo)
ALTER TABLE movimientos_inventario ADD COLUMN costo_anterior_x1000 INTEGER;
