-- Café_Café · menú inicial (categorías, productos e insumos)
-- Precios en centavos (L 80.00 se guarda como 8000).
-- Los insumos arrancan con stock 0 y costo 0: se llenan desde la pantalla de Insumos.
-- Las recetas se cargan aparte, cuando el dueño defina las cantidades.

INSERT INTO categorias (id_categoria, nombre, icono, orden) VALUES
    (1, 'Sodas & Bobbas', 'soda', 1),
    (2, 'Smoothies', 'smoothie', 2),
    (3, 'Kitchen''s Made', 'cocina', 3),
    (4, 'Other Drinks', 'taza', 4),
    (5, 'Nevadas', 'nevada', 5),
    (6, 'Frappés', 'frappe', 6),
    (7, 'Waffles & Crepas', 'waffle', 7),
    (8, 'Café Caliente', 'taza', 8),
    (9, 'Chocolate & Té', 'taza', 9),
    (10, 'Cold Drinks', 'soda', 10),
    (11, 'Extras', 'extra', 11);

-- Sodas & Bobbas
INSERT INTO productos (id_categoria, nombre, precio_cent) VALUES
    (1, 'Soda Italiana', 8000),
    (1, 'Bobba Pop', 9500);

-- Smoothies
INSERT INTO productos (id_categoria, nombre, precio_cent) VALUES
    (2, 'Smoothie Fresa', 10000),
    (2, 'Smoothie Mango Moy', 10000),
    (2, 'Smoothie Mangada', 10000),
    (2, 'Smoothie Fresa Moy', 10000);

-- Kitchens Made
INSERT INTO productos (id_categoria, nombre, precio_cent) VALUES
    (3, 'Panini Sándwich', 13000),
    (3, 'Croissant', 6800),
    (3, 'Pan con Frijoles y quesillo', 4800),
    (3, 'Sándwich', 4800),
    (3, 'Empanadas de pollo', 7500);

-- Other Drinks
INSERT INTO productos (id_categoria, nombre, precio_cent) VALUES
    (4, 'Chai Latte (14 oz)', 9000),
    (4, 'Chocolate Helado (14 oz)', 9000),
    (4, 'Chocolate Blanco Helado (14 oz)', 9000),
    (4, 'Matcha Latte (14 oz)', 10000),
    (4, 'Matcha Latte con crema', 11500),
    (4, 'Matcha Latte Sabor Rosa/Lavanda', 13000),
    (4, 'Taro Latte (14 oz)', 10000),
    (4, 'Taro Latte con crema', 11500),
    (4, 'Pistachio Latte (14 oz)', 11000),
    (4, 'Pistachio Latte con crema', 12000),
    (4, 'Limonada con agua (14 oz)', 7500),
    (4, 'Limonada con soda (14 oz)', 8500),
    (4, 'Té Frío (14 oz)', 8000);

-- Nevadas
INSERT INTO productos (id_categoria, nombre, precio_cent) VALUES
    (5, 'Nevada Fresa (14 oz)', 10000),
    (5, 'Nevada Mora (14 oz)', 10000),
    (5, 'Nevada Mora con oreo (14 oz)', 10000),
    (5, 'Nevada Piña Colada (14 oz)', 10000);

-- Frappés
INSERT INTO productos (id_categoria, nombre, precio_cent) VALUES
    (6, 'Frappé Café-Café', 10000),
    (6, 'Frappé Maní', 11000),
    (6, 'Frappé Nutella', 11000),
    (6, 'Frappé Oreo', 11000),
    (6, 'Chai Frappé', 11000),
    (6, 'Moka Frappé', 11000),
    (6, 'Caramel Coconut Frappé', 11000),
    (6, 'Frappé Horchata', 11000),
    (6, 'Matcha Frappé', 11000),
    (6, 'Taro Frappé', 11000),
    (6, 'Frappé Oreo Menta', 12000),
    (6, 'Pistacho Frappé', 12000);

-- Waffles & Crepas
INSERT INTO productos (id_categoria, nombre, precio_cent) VALUES
    (7, 'Buttermilk Waffle', 10000),
    (7, 'Waffle Fresa Banano', 13000),
    (7, 'Waffle Banano/Caramelo', 13000),
    (7, 'Nutella Waffle', 13000),
    (7, 'Crepa de Cheesecake', 13000),
    (7, 'Crepa de Nutella', 13000),
    (7, 'Panqueques', 10000);

-- Café Caliente
INSERT INTO productos (id_categoria, nombre, precio_cent) VALUES
    (8, 'Espresso (1 onz)', 4000),
    (8, 'Americano (8 onzas)', 4500),
    (8, 'Americano (12 onzas)', 5500),
    (8, 'Capuccino (8 onzas)', 5500),
    (8, 'Capuccino (12 onzas)', 6500),
    (8, 'Latte (8 onzas)', 5500),
    (8, 'Latte (12 onzas)', 6500),
    (8, 'Mocha (12 onzas)', 7000),
    (8, 'Mocha Blanco (12 onzas)', 7500),
    (8, 'Bombom', 5000),
    (8, 'Cortadito', 5000),
    (8, 'Latte Crema Caramelo', 8000),
    (8, 'Café Caramelo', 8000);

-- Chocolate & Té
INSERT INTO productos (id_categoria, nombre, precio_cent) VALUES
    (9, 'Chocolate Clásico (12 oz)', 7000),
    (9, 'Chocolate con crema (12 oz)', 7500),
    (9, 'Chocolate con malvavisco (12 oz)', 9000),
    (9, 'Chocolate Blanco (12 oz)', 8000),
    (9, 'Té (8 oz)', 4000),
    (9, 'Té (12 oz)', 5000),
    (9, 'Chai Latte (12 oz)', 8000),
    (9, 'Matcha (12 oz)', 10000),
    (9, 'Taro (12 oz)', 10000),
    (9, 'Pistacho Latte (12 oz)', 10000),
    (9, 'Pinol (12 oz)', 7000);

-- Cold Drinks
INSERT INTO productos (id_categoria, nombre, precio_cent) VALUES
    (10, 'Granitas (14 oz)', 7000),
    (10, 'Granita con crema (14 oz)', 8500),
    (10, 'Granita caramelo (14 oz)', 8500),
    (10, 'Bandera (14 oz)', 9000),
    (10, 'Mochaccino Supreme (14 oz)', 9000),
    (10, 'Mochaccino Blanco (14 oz)', 9000),
    (10, 'Iced Coffee (14 oz)', 7000),
    (10, 'Latte Helado (14 oz)', 8500),
    (10, 'Latte Helado Saborizado', 10000),
    (10, 'Latte Crema Caramelo Helado', 10000),
    (10, 'Mochalata (14 oz)', 10000),
    (10, 'Machiatto (14 oz)', 10000),
    (10, 'Moka Menta (14 oz)', 11000),
    (10, 'Moka Blanco (14 oz)', 10000);

-- Extras
INSERT INTO productos (id_categoria, nombre, precio_cent) VALUES
    (11, 'Extra Leche Deslactosada/Descremada', 1500),
    (11, 'Extra Crema', 2500),
    (11, 'Extra Saborizantes', 2500);

INSERT INTO insumos (nombre, unidad_base) VALUES
    ('Café en grano (Espresso)', 'g'),
    ('Leche Entera', 'ml'),
    ('Leche Deslactosada', 'ml'),
    ('Azúcar Blanca', 'g'),
    ('Sirope de Caramelo', 'ml'),
    ('Sirope de Vainilla', 'ml'),
    ('Té Matcha en polvo', 'g'),
    ('Té Chai en polvo', 'g'),
    ('Taro en polvo', 'g'),
    ('Polvo Base Frappé', 'g'),
    ('Vasos 14 oz (Helado)', 'ud'),
    ('Vasos 12 oz (Caliente)', 'ud'),
    ('Tapas Domo', 'ud'),
    ('Pajillas', 'ud');
