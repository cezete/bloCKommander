CREATE SCHEMA IF NOT EXISTS blockcommander;

CREATE TABLE IF NOT EXISTS blockcommander.developers (
    player_uuid uuid PRIMARY KEY,
    current_name varchar(64) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS blockcommander.halls (
    hall_id uuid PRIMARY KEY,
    create_request_id uuid NOT NULL UNIQUE,
    owner_uuid uuid NOT NULL REFERENCES blockcommander.developers(player_uuid),
    hall_name varchar(96) NOT NULL,
    world_uuid uuid NOT NULL,
    world_name varchar(128) NOT NULL,
    dimension_key varchar(128) NOT NULL,
    min_x integer NOT NULL, min_y integer NOT NULL, min_z integer NOT NULL,
    max_x integer NOT NULL, max_y integer NOT NULL, max_z integer NOT NULL,
    occupied_min_x integer NOT NULL, occupied_min_y integer NOT NULL, occupied_min_z integer NOT NULL,
    occupied_max_x integer NOT NULL, occupied_max_y integer NOT NULL, occupied_max_z integer NOT NULL,
    orientation varchar(8) NOT NULL CHECK (orientation IN ('NORTH','SOUTH','EAST','WEST')),
    material_name varchar(64) NOT NULL,
    material_id integer NOT NULL,
    generator_version integer NOT NULL,
    generator_params jsonb NOT NULL,
    lifecycle varchar(16) NOT NULL CHECK (lifecycle IN ('BUILDING','ACTIVE','DELETING','ERROR')),
    last_error text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS blockcommander.hall_controls (
    hall_id uuid NOT NULL REFERENCES blockcommander.halls(hall_id) ON DELETE CASCADE,
    world_uuid uuid NOT NULL,
    kind varchar(32) NOT NULL,
    x integer NOT NULL, y integer NOT NULL, z integer NOT NULL,
    PRIMARY KEY (hall_id, kind),
    UNIQUE (world_uuid, kind, x, y, z)
);

CREATE INDEX IF NOT EXISTS halls_owner_idx ON blockcommander.halls(owner_uuid);
CREATE INDEX IF NOT EXISTS halls_world_idx ON blockcommander.halls(world_uuid);
