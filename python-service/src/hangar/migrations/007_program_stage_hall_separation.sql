CREATE TABLE blockcommander.programs (
    program_id uuid PRIMARY KEY,
    owner_uuid uuid NOT NULL
        REFERENCES blockcommander.developers(player_uuid) ON DELETE RESTRICT,
    program_name varchar(96) NOT NULL,
    root_stage_id uuid NOT NULL,
    root_stage_kind varchar(32)
        GENERATED ALWAYS AS ('ROOT'::varchar(32)) STORED,
    saved_version bigint NOT NULL DEFAULT 0 CHECK (saved_version >= 0),
    lifecycle varchar(16) NOT NULL DEFAULT 'ACTIVE'
        CHECK (lifecycle IN ('ACTIVE')),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE blockcommander.stages
    ADD COLUMN program_id uuid,
    ADD COLUMN stage_kind varchar(32);

-- Each legacy Hall owned exactly one Stage. Preserve that Stage and its version,
-- and introduce a new Program around it without rewriting any Stage/Graph row.
INSERT INTO blockcommander.programs (
    program_id, owner_uuid, program_name, root_stage_id, saved_version,
    created_at, updated_at
)
SELECT gen_random_uuid(), h.owner_uuid, s.stage_name, s.stage_id,
       s.saved_version, s.created_at, s.updated_at
  FROM blockcommander.stages s
  JOIN blockcommander.halls h ON h.hall_id = s.hall_id;

UPDATE blockcommander.stages s
   SET program_id = p.program_id,
       stage_kind = 'ROOT'
  FROM blockcommander.programs p
 WHERE p.root_stage_id = s.stage_id;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM blockcommander.stages
         WHERE program_id IS NULL OR stage_kind IS NULL
    ) THEN
        RAISE EXCEPTION 'CK-06/A1 backfill left a Stage without a Program';
    END IF;
END;
$$;

ALTER TABLE blockcommander.stages
    ALTER COLUMN program_id SET NOT NULL,
    ALTER COLUMN stage_kind SET NOT NULL,
    ADD CONSTRAINT stages_stage_kind_check
        CHECK (stage_kind IN ('ROOT', 'PROGRAM_BODY')),
    ADD CONSTRAINT stages_program_id_fkey
        FOREIGN KEY (program_id)
        REFERENCES blockcommander.programs(program_id) ON DELETE RESTRICT,
    ADD CONSTRAINT stages_program_stage_key
        UNIQUE (program_id, stage_id),
    ADD CONSTRAINT stages_program_stage_kind_key
        UNIQUE (program_id, stage_id, stage_kind);

CREATE UNIQUE INDEX stages_one_root_per_program_idx
    ON blockcommander.stages(program_id)
    WHERE stage_kind = 'ROOT';

ALTER TABLE blockcommander.programs
    ADD CONSTRAINT programs_root_stage_fkey
        FOREIGN KEY (program_id, root_stage_id, root_stage_kind)
        REFERENCES blockcommander.stages(program_id, stage_id, stage_kind)
        ON DELETE RESTRICT
        DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE blockcommander.program_stage_links (
    program_id uuid NOT NULL
        REFERENCES blockcommander.programs(program_id) ON DELETE RESTRICT,
    parent_stage_id uuid NOT NULL,
    program_block_instance_id uuid NOT NULL,
    child_stage_id uuid NOT NULL,
    child_stage_kind varchar(32)
        GENERATED ALWAYS AS ('PROGRAM_BODY'::varchar(32)) STORED,
    link_state varchar(16) NOT NULL
        CHECK (link_state IN ('ACTIVE', 'RETAINED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (
        program_id, parent_stage_id, program_block_instance_id, child_stage_id
    ),
    CONSTRAINT program_stage_links_distinct_stage_check
        CHECK (parent_stage_id <> child_stage_id),
    CONSTRAINT program_stage_links_parent_fkey
        FOREIGN KEY (program_id, parent_stage_id)
        REFERENCES blockcommander.stages(program_id, stage_id)
        ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT program_stage_links_child_fkey
        FOREIGN KEY (program_id, child_stage_id, child_stage_kind)
        REFERENCES blockcommander.stages(program_id, stage_id, stage_kind)
        ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED
);

CREATE UNIQUE INDEX program_stage_links_active_block_idx
    ON blockcommander.program_stage_links(
        program_id, parent_stage_id, program_block_instance_id
    )
    WHERE link_state = 'ACTIVE';

CREATE UNIQUE INDEX program_stage_links_active_child_idx
    ON blockcommander.program_stage_links(program_id, child_stage_id)
    WHERE link_state = 'ACTIVE';

CREATE TABLE blockcommander.hall_program_mounts (
    hall_id uuid PRIMARY KEY
        REFERENCES blockcommander.halls(hall_id) ON DELETE CASCADE,
    program_id uuid NOT NULL
        REFERENCES blockcommander.programs(program_id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX hall_program_mounts_program_idx
    ON blockcommander.hall_program_mounts(program_id);

INSERT INTO blockcommander.hall_program_mounts (
    hall_id, program_id, created_at, updated_at
)
SELECT s.hall_id, s.program_id, s.created_at, s.updated_at
  FROM blockcommander.stages s;

-- hall_id is retained only as a temporary projection compatibility field.
-- The mount table is canonical; deleting a Hall may only null this legacy field
-- and cascade-delete its mount, never the Program, Stage, or Graph content.
ALTER TABLE blockcommander.stages
    DROP CONSTRAINT stages_hall_id_fkey,
    DROP CONSTRAINT stages_hall_id_key,
    ALTER COLUMN hall_id DROP NOT NULL,
    ADD CONSTRAINT stages_legacy_hall_id_fkey
        FOREIGN KEY (hall_id)
        REFERENCES blockcommander.halls(hall_id) ON DELETE SET NULL;

CREATE INDEX stages_legacy_hall_idx
    ON blockcommander.stages(hall_id)
    WHERE hall_id IS NOT NULL;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM blockcommander.programs p
          LEFT JOIN blockcommander.stages s
            ON s.program_id = p.program_id
           AND s.stage_id = p.root_stage_id
           AND s.stage_kind = 'ROOT'
         WHERE s.stage_id IS NULL
    ) THEN
        RAISE EXCEPTION 'CK-06/A1 Program root invariant failed';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM blockcommander.stages s
          LEFT JOIN blockcommander.programs p ON p.program_id = s.program_id
         WHERE p.program_id IS NULL
    ) THEN
        RAISE EXCEPTION 'CK-06/A1 Stage ownership invariant failed';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM blockcommander.stages s
          JOIN blockcommander.halls h ON h.hall_id = s.hall_id
          LEFT JOIN blockcommander.hall_program_mounts m
            ON m.hall_id = h.hall_id AND m.program_id = s.program_id
         WHERE m.hall_id IS NULL
    ) THEN
        RAISE EXCEPTION 'CK-06/A1 Hall mount backfill failed';
    END IF;
END;
$$;
