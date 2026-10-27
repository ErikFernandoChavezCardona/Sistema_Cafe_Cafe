-- Quién hizo cada movimiento de inventario (compras y ajustes a mano, y el cajero en las ventas)
ALTER TABLE movimientos_inventario ADD COLUMN id_usuario INTEGER REFERENCES usuarios (id_usuario);
