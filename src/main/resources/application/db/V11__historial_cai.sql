-- Historial de autorizaciones del SAR (CAI): una fila por cada talonario de facturas comprado.
CREATE TABLE autorizaciones_cai (
    id_cai        INTEGER PRIMARY KEY AUTOINCREMENT,
    cai           TEXT NOT NULL UNIQUE COLLATE NOCASE,
    prefijo       TEXT NOT NULL,
    rango_desde   INTEGER NOT NULL CHECK (rango_desde > 0),
    rango_hasta   INTEGER NOT NULL,
    fecha_limite  TEXT NOT NULL,
    fecha_compra  TEXT,
    imprenta      TEXT NOT NULL DEFAULT '',
    costo_cent    INTEGER NOT NULL DEFAULT 0 CHECK (costo_cent >= 0),
    nota          TEXT NOT NULL DEFAULT '',
    id_usuario    INTEGER REFERENCES usuarios (id_usuario),
    registrado_en TEXT NOT NULL DEFAULT (datetime('now', 'localtime')),
    CHECK (rango_hasta >= rango_desde)
);

-- El CAI que ya está configurado hoy pasa al historial (sin datos de compra, que no se conocen)
INSERT INTO autorizaciones_cai (cai, prefijo, rango_desde, rango_hasta, fecha_limite)
SELECT c.valor, p.valor, CAST(d.valor AS INTEGER), CAST(h.valor AS INTEGER), l.valor
FROM config c, config p, config d, config h, config l
WHERE c.clave = 'factura_cai' AND p.clave = 'factura_prefijo' AND d.clave = 'factura_rango_desde'
  AND h.clave = 'factura_rango_hasta' AND l.clave = 'factura_fecha_limite'
  AND c.valor <> '' AND p.valor <> '' AND l.valor <> ''
  AND CAST(d.valor AS INTEGER) > 0 AND CAST(h.valor AS INTEGER) >= CAST(d.valor AS INTEGER);
