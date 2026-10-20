INSERT INTO blockcommander.program_block_types
    (type_key, display_name, material_name, default_label, default_description, display_definition)
VALUES
    ('IF', 'IF', 'YELLOW_GLAZED_TERRACOTTA', 'IF', 'Feltételes programelem; végrehajtási szemantikája későbbi feladat.',
     '{"front":"label","back":"instance_id","left":"type","right":"description"}'::jsonb),
    ('FOR', 'FOR', 'CYAN_GLAZED_TERRACOTTA', 'FOR', 'Ciklus programelem; végrehajtási szemantikája későbbi feladat.',
     '{"front":"label","back":"instance_id","left":"type","right":"description"}'::jsonb),
    ('FUN', 'FUN', 'PURPLE_GLAZED_TERRACOTTA', 'FUN', 'Függvény programelem; végrehajtási szemantikája későbbi feladat.',
     '{"front":"label","back":"instance_id","left":"type","right":"description"}'::jsonb)
ON CONFLICT (type_key) DO UPDATE SET
    display_name=excluded.display_name,
    material_name=excluded.material_name,
    default_label=excluded.default_label,
    default_description=excluded.default_description,
    display_definition=excluded.display_definition;
