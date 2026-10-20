CREATE TABLE IF NOT EXISTS blockcommander.program_block_types (
    type_key varchar(32) PRIMARY KEY,
    display_name varchar(64) NOT NULL,
    material_name varchar(64) NOT NULL,
    default_label varchar(32) NOT NULL,
    default_description varchar(256),
    display_definition jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now()
);

INSERT INTO blockcommander.program_block_types
    (type_key, display_name, material_name, default_label, default_description, display_definition)
VALUES
    ('START', 'START', 'LIME_GLAZED_TERRACOTTA', 'START', 'Program kezdőeleme',
     '{"front":"label","back":"instance_id","left":"type","right":"description"}'::jsonb),
    ('STOP', 'STOP', 'RED_GLAZED_TERRACOTTA', 'STOP', 'Program záróeleme',
     '{"front":"label","back":"instance_id","left":"type","right":"description"}'::jsonb)
ON CONFLICT (type_key) DO NOTHING;

CREATE TABLE IF NOT EXISTS blockcommander.stages (
    stage_id uuid PRIMARY KEY,
    hall_id uuid NOT NULL UNIQUE REFERENCES blockcommander.halls(hall_id) ON DELETE CASCADE,
    stage_name varchar(96) NOT NULL,
    origin_rel_x integer NOT NULL,
    origin_rel_y integer NOT NULL,
    origin_rel_z integer NOT NULL,
    size_x integer NOT NULL CHECK (size_x > 0),
    size_y integer NOT NULL CHECK (size_y > 0),
    size_z integer NOT NULL CHECK (size_z > 0),
    saved_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS blockcommander.stage_blocks (
    instance_id uuid PRIMARY KEY,
    stage_id uuid NOT NULL REFERENCES blockcommander.stages(stage_id) ON DELETE CASCADE,
    type_key varchar(32) NOT NULL REFERENCES blockcommander.program_block_types(type_key),
    rel_x integer NOT NULL,
    rel_y integer NOT NULL,
    rel_z integer NOT NULL,
    orientation varchar(8) NOT NULL CHECK (orientation IN ('NORTH','SOUTH','EAST','WEST')),
    label varchar(32) NOT NULL,
    description varchar(256),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (stage_id, rel_x, rel_y, rel_z)
);

CREATE INDEX IF NOT EXISTS stage_blocks_stage_idx
    ON blockcommander.stage_blocks(stage_id);
