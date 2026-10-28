-- Facturación automática al cobrar.
-- Se reconstruye facturas_venta para poder ANULAR una factura y emitir otra sobre la misma venta
-- (por ejemplo cuando el cliente pide el RTN en el último momento). El número de una factura anulada no se reutiliza.
CREATE TABLE facturas_venta_nueva (
    id_factura_venta  INTEGER PRIMARY KEY AUTOINCREMENT,
    id_venta          INTEGER NOT NULL REFERENCES ventas (id_venta),
    numero            TEXT NOT NULL UNIQUE,
    cai               TEXT,
    rango_desde       TEXT,
    rango_hasta       TEXT,
    fecha_limite      TEXT,
    rtn_cliente       TEXT,
    nombre_cliente    TEXT NOT NULL DEFAULT 'Consumidor final',
    subtotal_cent     INTEGER NOT NULL CHECK (subtotal_cent >= 0),
    impuesto_cent     INTEGER NOT NULL DEFAULT 0 CHECK (impuesto_cent >= 0),
    impuesto_pct_x100 INTEGER NOT NULL DEFAULT 0,
    total_cent        INTEGER NOT NULL CHECK (total_cent >= 0),
    id_usuario        INTEGER REFERENCES usuarios (id_usuario),
    emitida_en        TEXT NOT NULL DEFAULT (datetime('now', 'localtime')),
    anulada           INTEGER NOT NULL DEFAULT 0 CHECK (anulada IN (0, 1)),
    motivo_anulacion  TEXT,
    id_usuario_anulo  INTEGER REFERENCES usuarios (id_usuario),
    anulada_en        TEXT
);

INSERT INTO facturas_venta_nueva (id_factura_venta, id_venta, numero, cai, rtn_cliente, nombre_cliente,
                                  subtotal_cent, impuesto_cent, total_cent, emitida_en)
SELECT id_factura_venta, id_venta, numero, cai, rtn_cliente, nombre_cliente,
       subtotal_cent, impuesto_cent, total_cent, emitida_en
FROM facturas_venta;

DROP TABLE facturas_venta;
ALTER TABLE facturas_venta_nueva RENAME TO facturas_venta;

-- Una venta solo puede tener UNA factura vigente (las anuladas quedan en el historial)
CREATE UNIQUE INDEX ux_factura_venta_vigente ON facturas_venta (id_venta) WHERE anulada = 0;
CREATE INDEX ix_factura_emitida ON facturas_venta (emitida_en);

-- Datos que salen impresos en la factura
INSERT OR IGNORE INTO config (clave, valor) VALUES
    ('factura_prefijo', '000-001-01'),
    ('negocio_rtn', ''),
    ('negocio_direccion', ''),
    ('negocio_telefono', ''),
    ('negocio_email', ''),
    ('factura_imprimir_auto', '0');

-- Los precios del menú ya incluyen ISV: si seguía en 0 (valor de fábrica) se pone 15 %. Confírmalo con tu contador.
UPDATE config SET valor = '15' WHERE clave = 'impuesto_pct' AND valor = '0';
