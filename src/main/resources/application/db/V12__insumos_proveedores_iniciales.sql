-- Café_Café · proveedores e insumos iniciales (tomados de 40 facturas de octubre 2026)
-- Costos SIN ISV. Stock en 0. Sin recetas. Solo entran insumos con cantidad de compra conocida.

INSERT OR IGNORE INTO proveedores (nombre, rtn, telefono) VALUES
    ('Bodega La Constitución', '1601-1975-008436', '2643-2025'),
    ('Panificadora La Popular', '1307-1977-000616', NULL),
    ('Inversiones Tencoa', '1601-1991-005305', '9807-1392'),
    ('Abarrotería Sagastume', '1601-1990-008361', '9830-6669'),
    ('Super Excel', '1601-9017-966497', '9569-4350'),
    ('Distribuciones Álvarez', '0501-1983-083171', '3204-2010'),
    ('Leyde (Leche y Derivados)', NULL, '800-2440-9090'),
    ('FIDES S.A. de C.V.', '0511-9015-752080', '2551-7430')
;

UPDATE insumos SET costo_cent_x1000 = 3306, id_proveedor = COALESCE((SELECT id_proveedor FROM proveedores WHERE nombre = 'Leyde (Leche y Derivados)'), id_proveedor) WHERE nombre = 'Leche Entera';
UPDATE insumos SET costo_cent_x1000 = 3224, id_proveedor = COALESCE(NULL, id_proveedor) WHERE nombre = 'Leche Deslactosada';
UPDATE insumos SET costo_cent_x1000 = 45404, id_proveedor = COALESCE((SELECT id_proveedor FROM proveedores WHERE nombre = 'Super Excel'), id_proveedor) WHERE nombre = 'Té Chai en polvo';
INSERT OR IGNORE INTO insumos (nombre, unidad_base, costo_cent_x1000, id_proveedor) VALUES ('Crema dulce 2 pinos litro', 'ml', 15217, (SELECT id_proveedor FROM proveedores WHERE nombre = 'Abarrotería Sagastume'));
INSERT OR IGNORE INTO insumos (nombre, unidad_base, costo_cent_x1000, id_proveedor) VALUES ('2 pinos entera polvo', 'g', 34286, (SELECT id_proveedor FROM proveedores WHERE nombre = 'Abarrotería Sagastume'));
INSERT OR IGNORE INTO insumos (nombre, unidad_base, costo_cent_x1000, id_proveedor) VALUES ('Tapa #8 Negra', 'ud', 104340, NULL);
INSERT OR IGNORE INTO insumos (nombre, unidad_base, costo_cent_x1000, id_proveedor) VALUES ('vaso carton 8oz', 'ud', 173920, NULL);
INSERT OR IGNORE INTO insumos (nombre, unidad_base, costo_cent_x1000, id_proveedor) VALUES ('Manteca leyde AMA', 'g', 22068, NULL);
INSERT OR IGNORE INTO insumos (nombre, unidad_base, costo_cent_x1000, id_proveedor) VALUES ('Queso leyde', 'g', 20529, (SELECT id_proveedor FROM proveedores WHERE nombre = 'Leyde (Leche y Derivados)'));
INSERT OR IGNORE INTO insumos (nombre, unidad_base, costo_cent_x1000, id_proveedor) VALUES ('jamon de pollo', 'g', 22727, (SELECT id_proveedor FROM proveedores WHERE nombre = 'Abarrotería Sagastume'));
INSERT OR IGNORE INTO insumos (nombre, unidad_base, costo_cent_x1000, id_proveedor) VALUES ('manga ajusta P/Vaso de café', 'ud', 139120, (SELECT id_proveedor FROM proveedores WHERE nombre = 'Distribuciones Álvarez'));
INSERT OR IGNORE INTO insumos (nombre, unidad_base, costo_cent_x1000, id_proveedor) VALUES ('dulce de leche nestle', 'g', 16018, (SELECT id_proveedor FROM proveedores WHERE nombre = 'Abarrotería Sagastume'));
INSERT OR IGNORE INTO insumos (nombre, unidad_base, costo_cent_x1000, id_proveedor) VALUES ('Pull man P', 'ud', 5500000, (SELECT id_proveedor FROM proveedores WHERE nombre = 'Panificadora La Popular'));
INSERT OR IGNORE INTO insumos (nombre, unidad_base, costo_cent_x1000, id_proveedor) VALUES ('queso procesado 1/2 libra', 'g', 23366, (SELECT id_proveedor FROM proveedores WHERE nombre = 'Abarrotería Sagastume'));
INSERT OR IGNORE INTO insumos (nombre, unidad_base, costo_cent_x1000, id_proveedor) VALUES ('aderezo mck', 'g', 13914, (SELECT id_proveedor FROM proveedores WHERE nombre = 'Abarrotería Sagastume'));
INSERT OR IGNORE INTO insumos (nombre, unidad_base, costo_cent_x1000, id_proveedor) VALUES ('leche condensada elmigo', 'g', 8695, (SELECT id_proveedor FROM proveedores WHERE nombre = 'Bodega La Constitución'));
INSERT OR IGNORE INTO insumos (nombre, unidad_base, costo_cent_x1000, id_proveedor) VALUES ('nestle dulce de leche lata', 'g', 16247, NULL);
INSERT OR IGNORE INTO insumos (nombre, unidad_base, costo_cent_x1000, id_proveedor) VALUES ('leche evaporada', 'g', 10000, NULL);
INSERT OR IGNORE INTO insumos (nombre, unidad_base, costo_cent_x1000, id_proveedor) VALUES ('Frijoles La Rosa (bolsa 400 g)', 'g', 5870, (SELECT id_proveedor FROM proveedores WHERE nombre = 'Bodega La Constitución'));
INSERT OR IGNORE INTO insumos (nombre, unidad_base, costo_cent_x1000, id_proveedor) VALUES ('Azúcar morena (1 kg)', 'g', 3000, (SELECT id_proveedor FROM proveedores WHERE nombre = 'Bodega La Constitución'));
INSERT OR IGNORE INTO insumos (nombre, unidad_base, costo_cent_x1000, id_proveedor) VALUES ('El Panadero 5 libras', 'g', 2646, (SELECT id_proveedor FROM proveedores WHERE nombre = 'Bodega La Constitución'));
INSERT OR IGNORE INTO insumos (nombre, unidad_base, costo_cent_x1000, id_proveedor) VALUES ('Quesillo Leyde estilo sureño 400 g', 'g', 27000, (SELECT id_proveedor FROM proveedores WHERE nombre = 'Inversiones Tencoa'));
INSERT OR IGNORE INTO insumos (nombre, unidad_base, costo_cent_x1000, id_proveedor) VALUES ('Queso Pro Leyde 1/4 lb', 'g', 39823, (SELECT id_proveedor FROM proveedores WHERE nombre = 'Inversiones Tencoa'));
INSERT OR IGNORE INTO insumos (nombre, unidad_base, costo_cent_x1000, id_proveedor) VALUES ('Chori Parri Argentino 454 g', 'g', 19824, (SELECT id_proveedor FROM proveedores WHERE nombre = 'Abarrotería Sagastume'));
INSERT OR IGNORE INTO insumos (nombre, unidad_base, costo_cent_x1000, id_proveedor) VALUES ('Carnaval Limonada 350 ml', 'ml', 4286, (SELECT id_proveedor FROM proveedores WHERE nombre = 'Super Excel'));
INSERT OR IGNORE INTO insumos (nombre, unidad_base, costo_cent_x1000, id_proveedor) VALUES ('Porta vaso 4 espacios (paquete 25)', 'ud', 295640, (SELECT id_proveedor FROM proveedores WHERE nombre = 'Distribuciones Álvarez'));
INSERT OR IGNORE INTO insumos (nombre, unidad_base, costo_cent_x1000, id_proveedor) VALUES ('Bandeja 5x5 Arcopack (paquete 20)', 'ud', 326100, (SELECT id_proveedor FROM proveedores WHERE nombre = 'Distribuciones Álvarez'));
INSERT OR IGNORE INTO insumos (nombre, unidad_base, costo_cent_x1000, id_proveedor) VALUES ('Azúcar El Cañal 1 kg', 'g', 2900, (SELECT id_proveedor FROM proveedores WHERE nombre = 'Abarrotería Sagastume'));
INSERT OR IGNORE INTO insumos (nombre, unidad_base, costo_cent_x1000, id_proveedor) VALUES ('Azúcar en sobre (caja 150 und)', 'ud', 25333, (SELECT id_proveedor FROM proveedores WHERE nombre = 'Abarrotería Sagastume'));
INSERT OR IGNORE INTO insumos (nombre, unidad_base, costo_cent_x1000, id_proveedor) VALUES ('Azúcar Member en sobre (50 und)', 'ud', 40000, (SELECT id_proveedor FROM proveedores WHERE nombre = 'Abarrotería Sagastume'));
INSERT OR IGNORE INTO insumos (nombre, unidad_base, costo_cent_x1000, id_proveedor) VALUES ('Oreo 432 g', 'g', 22222, (SELECT id_proveedor FROM proveedores WHERE nombre = 'Abarrotería Sagastume'));
INSERT OR IGNORE INTO insumos (nombre, unidad_base, costo_cent_x1000, id_proveedor) VALUES ('Bisquick panqueque 96 oz', 'g', 9552, (SELECT id_proveedor FROM proveedores WHERE nombre = 'Abarrotería Sagastume'));
INSERT OR IGNORE INTO insumos (nombre, unidad_base, costo_cent_x1000, id_proveedor) VALUES ('Fécula de maíz (1 lb)', 'g', 3634, (SELECT id_proveedor FROM proveedores WHERE nombre = 'FIDES S.A. de C.V.'));
INSERT OR IGNORE INTO insumos (nombre, unidad_base, costo_cent_x1000, id_proveedor) VALUES ('Crema de queso en manga (2 lb)', 'g', 25358, (SELECT id_proveedor FROM proveedores WHERE nombre = 'FIDES S.A. de C.V.'));
INSERT OR IGNORE INTO insumos (nombre, unidad_base, costo_cent_x1000, id_proveedor) VALUES ('Cereza en manga (2 lb)', 'g', 15546, (SELECT id_proveedor FROM proveedores WHERE nombre = 'FIDES S.A. de C.V.'));
INSERT OR IGNORE INTO insumos (nombre, unidad_base, costo_cent_x1000, id_proveedor) VALUES ('Leche Leyde entera 3.7 L', 'ml', 3730, NULL);
INSERT OR IGNORE INTO insumos (nombre, unidad_base, costo_cent_x1000, id_proveedor) VALUES ('Sirope EE Original 24 oz', 'ml', 14415, NULL);
INSERT OR IGNORE INTO insumos (nombre, unidad_base, costo_cent_x1000, id_proveedor) VALUES ('Vaso #12 granita 98.5 mm + tapa domo (kit 50)', 'ud', 269560, (SELECT id_proveedor FROM proveedores WHERE nombre = 'Distribuciones Álvarez'));
INSERT OR IGNORE INTO insumos (nombre, unidad_base, costo_cent_x1000, id_proveedor) VALUES ('Vaso #16 granita 98.5 mm + tapa domo (kit 50)', 'ud', 286960, (SELECT id_proveedor FROM proveedores WHERE nombre = 'Distribuciones Álvarez'));
INSERT OR IGNORE INTO insumos (nombre, unidad_base, costo_cent_x1000, id_proveedor) VALUES ('Tenedor grande Plastival (25 und)', 'ud', 34800, (SELECT id_proveedor FROM proveedores WHERE nombre = 'Abarrotería Sagastume'));
INSERT OR IGNORE INTO insumos (nombre, unidad_base, costo_cent_x1000, id_proveedor) VALUES ('Cuchillo Mayab (paquete 40)', 'ud', 39125, (SELECT id_proveedor FROM proveedores WHERE nombre = 'Abarrotería Sagastume'));
INSERT OR IGNORE INTO insumos (nombre, unidad_base, costo_cent_x1000, id_proveedor) VALUES ('Bandeja de aluminio para pollo', 'ud', 1200000, (SELECT id_proveedor FROM proveedores WHERE nombre = 'Abarrotería Sagastume'));

-- Concentrados de fruta: sin cantidad de compra conocida, entran con costo 0 (se completa desde Insumos)
INSERT OR IGNORE INTO insumos (nombre, unidad_base) VALUES ('Concentrado de fresa', 'ml');
INSERT OR IGNORE INTO insumos (nombre, unidad_base) VALUES ('Concentrado de café', 'ml');
INSERT OR IGNORE INTO insumos (nombre, unidad_base) VALUES ('Concentrado piña colada', 'ml');
