-- Clientes frecuentes: se guardan solos cuando se factura con RTN
CREATE TABLE clientes (
    rtn        TEXT PRIMARY KEY,
    nombre     TEXT NOT NULL,
    veces      INTEGER NOT NULL DEFAULT 0,
    ultima_vez TEXT NOT NULL DEFAULT (datetime('now', 'localtime')),
    activo     INTEGER NOT NULL DEFAULT 1 CHECK (activo IN (0, 1))
);

INSERT OR IGNORE INTO clientes (rtn, nombre, veces, ultima_vez)
SELECT rtn_cliente, MAX(nombre_cliente), COUNT(*), MAX(emitida_en)
FROM facturas_venta
WHERE anulada = 0 AND rtn_cliente IS NOT NULL AND rtn_cliente <> ''
GROUP BY rtn_cliente;
