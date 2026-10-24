-- Café_Café · esquema inicial (SQLite)
-- Reglas del esquema:
--   * El dinero va en CENTAVOS como número entero (L 55.00 se guarda como 5500).
--   * Las fechas van como texto local AAAA-MM-DD HH:MM:SS.
--   * Los estados y métodos de pago tienen CHECK: no se puede guardar un texto distinto.
--   * Los insumos se guardan en su unidad más pequeña (g, ml o ud).

CREATE TABLE config (
    clave TEXT PRIMARY KEY,
    valor TEXT NOT NULL DEFAULT ''
);

CREATE TABLE usuarios (
    id_usuario    INTEGER PRIMARY KEY AUTOINCREMENT,
    nombre        TEXT NOT NULL,
    usuario       TEXT NOT NULL UNIQUE COLLATE NOCASE,
    password_hash TEXT NOT NULL,
    rol           TEXT NOT NULL DEFAULT 'Cajero' CHECK (rol IN ('Administrador', 'Cajero')),
    activo        INTEGER NOT NULL DEFAULT 1 CHECK (activo IN (0, 1)),
    creado_en     TEXT NOT NULL DEFAULT (datetime('now', 'localtime'))
);

CREATE TABLE categorias (
    id_categoria INTEGER PRIMARY KEY AUTOINCREMENT,
    nombre       TEXT NOT NULL UNIQUE,
    icono        TEXT NOT NULL DEFAULT 'taza',
    imagen       TEXT,
    orden        INTEGER NOT NULL DEFAULT 0,
    activo       INTEGER NOT NULL DEFAULT 1 CHECK (activo IN (0, 1))
);

CREATE TABLE productos (
    id_producto  INTEGER PRIMARY KEY AUTOINCREMENT,
    id_categoria INTEGER NOT NULL REFERENCES categorias (id_categoria),
    nombre       TEXT NOT NULL,
    precio_cent  INTEGER NOT NULL CHECK (precio_cent >= 0),
    imagen       TEXT,
    activo       INTEGER NOT NULL DEFAULT 1 CHECK (activo IN (0, 1)),
    UNIQUE (id_categoria, nombre)
);
CREATE INDEX ix_productos_categoria ON productos (id_categoria);

CREATE TABLE proveedores (
    id_proveedor INTEGER PRIMARY KEY AUTOINCREMENT,
    nombre       TEXT NOT NULL UNIQUE,
    rtn          TEXT,
    telefono     TEXT,
    activo       INTEGER NOT NULL DEFAULT 1 CHECK (activo IN (0, 1))
);

CREATE TABLE insumos (
    id_insumo        INTEGER PRIMARY KEY AUTOINCREMENT,
    nombre           TEXT NOT NULL UNIQUE,
    unidad_base      TEXT NOT NULL CHECK (unidad_base IN ('g', 'ml', 'ud')),
    stock            INTEGER NOT NULL DEFAULT 0,      -- puede quedar negativo si se vende sin stock: la app avisa, no bloquea
    stock_minimo     INTEGER NOT NULL DEFAULT 0,
    costo_cent_x1000 INTEGER NOT NULL DEFAULT 0,      -- costo en centavos de 1000 unidades base (1 kg, 1 litro o 1000 uds)
    id_proveedor     INTEGER REFERENCES proveedores (id_proveedor),
    imagen           TEXT,
    activo           INTEGER NOT NULL DEFAULT 1 CHECK (activo IN (0, 1))
);

-- Receta: cuánto insumo gasta cada producto por unidad vendida
CREATE TABLE recetas (
    id_producto INTEGER NOT NULL REFERENCES productos (id_producto) ON DELETE CASCADE,
    id_insumo   INTEGER NOT NULL REFERENCES insumos (id_insumo),
    cantidad    INTEGER NOT NULL CHECK (cantidad > 0),
    PRIMARY KEY (id_producto, id_insumo)
);

CREATE TABLE turnos_caja (
    id_turno            INTEGER PRIMARY KEY AUTOINCREMENT,
    id_usuario          INTEGER NOT NULL REFERENCES usuarios (id_usuario),
    apertura_en         TEXT NOT NULL DEFAULT (datetime('now', 'localtime')),
    monto_inicial_cent  INTEGER NOT NULL DEFAULT 0 CHECK (monto_inicial_cent >= 0),
    cierre_en           TEXT,
    monto_esperado_cent INTEGER,
    monto_contado_cent  INTEGER,
    diferencia_cent     INTEGER,
    estado              TEXT NOT NULL DEFAULT 'Abierta' CHECK (estado IN ('Abierta', 'Cerrada'))
);
-- Solo puede haber un turno abierto a la vez (una sola caja)
CREATE UNIQUE INDEX ux_turno_abierto ON turnos_caja (estado) WHERE estado = 'Abierta';

CREATE TABLE ventas (
    id_venta       INTEGER PRIMARY KEY AUTOINCREMENT,
    id_turno       INTEGER NOT NULL REFERENCES turnos_caja (id_turno),
    id_usuario     INTEGER NOT NULL REFERENCES usuarios (id_usuario),
    nombre_comanda TEXT NOT NULL,
    estado         TEXT NOT NULL DEFAULT 'Pendiente' CHECK (estado IN ('Pendiente', 'Completada', 'Cancelada')),
    metodo_pago    TEXT CHECK (metodo_pago IN ('Efectivo', 'Tarjeta', 'Transferencia')),
    total_cent     INTEGER NOT NULL DEFAULT 0 CHECK (total_cent >= 0),
    recibido_cent  INTEGER NOT NULL DEFAULT 0,
    cambio_cent    INTEGER NOT NULL DEFAULT 0,
    creada_en      TEXT NOT NULL DEFAULT (datetime('now', 'localtime')),
    cobrada_en     TEXT
);
CREATE INDEX ix_ventas_turno ON ventas (id_turno);
CREATE INDEX ix_ventas_estado ON ventas (estado);

CREATE TABLE detalle_ventas (
    id_detalle       INTEGER PRIMARY KEY AUTOINCREMENT,
    id_venta         INTEGER NOT NULL REFERENCES ventas (id_venta) ON DELETE CASCADE,
    id_producto      INTEGER NOT NULL REFERENCES productos (id_producto),
    cantidad         INTEGER NOT NULL CHECK (cantidad > 0),
    precio_unit_cent INTEGER NOT NULL CHECK (precio_unit_cent >= 0),
    subtotal_cent    INTEGER NOT NULL CHECK (subtotal_cent >= 0)
);
CREATE INDEX ix_detalle_venta ON detalle_ventas (id_venta);

CREATE TABLE facturas_venta (
    id_factura_venta INTEGER PRIMARY KEY AUTOINCREMENT,
    id_venta         INTEGER NOT NULL UNIQUE REFERENCES ventas (id_venta),
    numero           TEXT NOT NULL UNIQUE,
    cai              TEXT,
    rtn_cliente      TEXT,
    nombre_cliente   TEXT NOT NULL DEFAULT 'Consumidor final',
    subtotal_cent    INTEGER NOT NULL CHECK (subtotal_cent >= 0),
    impuesto_cent    INTEGER NOT NULL DEFAULT 0 CHECK (impuesto_cent >= 0),
    total_cent       INTEGER NOT NULL CHECK (total_cent >= 0),
    emitida_en       TEXT NOT NULL DEFAULT (datetime('now', 'localtime'))
);

CREATE TABLE facturas_compra (
    id_factura_compra INTEGER PRIMARY KEY AUTOINCREMENT,
    id_proveedor      INTEGER NOT NULL REFERENCES proveedores (id_proveedor),
    numero            TEXT NOT NULL,
    fecha             TEXT NOT NULL,
    total_cent        INTEGER NOT NULL CHECK (total_cent >= 0),
    nota              TEXT,
    creada_en         TEXT NOT NULL DEFAULT (datetime('now', 'localtime')),
    UNIQUE (id_proveedor, numero)
);

-- Gastos del negocio. Si pagado_desde_caja = 1 se descuenta del efectivo del turno.
CREATE TABLE egresos (
    id_egreso         INTEGER PRIMARY KEY AUTOINCREMENT,
    id_turno          INTEGER REFERENCES turnos_caja (id_turno),
    categoria         TEXT NOT NULL DEFAULT 'General',
    descripcion       TEXT NOT NULL,
    monto_cent        INTEGER NOT NULL CHECK (monto_cent > 0),
    pagado_desde_caja INTEGER NOT NULL DEFAULT 1 CHECK (pagado_desde_caja IN (0, 1)),
    id_factura_compra INTEGER REFERENCES facturas_compra (id_factura_compra),
    creado_en         TEXT NOT NULL DEFAULT (datetime('now', 'localtime'))
);
CREATE INDEX ix_egresos_turno ON egresos (id_turno);

-- Efectivo que entra a caja chica durante el turno (reposición, cambio, etc.)
CREATE TABLE ingresos_caja (
    id_ingreso INTEGER PRIMARY KEY AUTOINCREMENT,
    id_turno   INTEGER NOT NULL REFERENCES turnos_caja (id_turno),
    concepto   TEXT NOT NULL,
    monto_cent INTEGER NOT NULL CHECK (monto_cent > 0),
    creado_en  TEXT NOT NULL DEFAULT (datetime('now', 'localtime'))
);

-- Historial del inventario: cantidad positiva suma stock, negativa resta
CREATE TABLE movimientos_inventario (
    id_movimiento     INTEGER PRIMARY KEY AUTOINCREMENT,
    id_insumo         INTEGER NOT NULL REFERENCES insumos (id_insumo),
    tipo              TEXT NOT NULL CHECK (tipo IN ('Compra', 'Venta', 'Devolucion', 'Ajuste')),
    cantidad          INTEGER NOT NULL,
    id_venta          INTEGER REFERENCES ventas (id_venta),
    id_factura_compra INTEGER REFERENCES facturas_compra (id_factura_compra),
    nota              TEXT,
    creado_en         TEXT NOT NULL DEFAULT (datetime('now', 'localtime'))
);
CREATE INDEX ix_mov_inventario_insumo ON movimientos_inventario (id_insumo);

-- Datos del negocio. El impuesto y los datos de factura los confirma el contador antes de usarlos.
INSERT INTO config (clave, valor) VALUES
    ('nombre_negocio', 'Café Café'),
    ('moneda', 'L'),
    ('impuesto_pct', '0'),
    ('factura_cai', ''),
    ('factura_rango_desde', ''),
    ('factura_rango_hasta', ''),
    ('factura_fecha_limite', ''),
    ('factura_siguiente', '1');
