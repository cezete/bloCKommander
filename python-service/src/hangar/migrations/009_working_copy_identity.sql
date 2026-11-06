-- CK-07/A1.1: identity only. No content copy, base snapshot or new SAVE semantics.
CREATE TABLE IF NOT EXISTS blockcommander.working_copies (
    working_copy_id uuid PRIMARY KEY,
    program_id uuid NOT NULL
        REFERENCES blockcommander.programs(program_id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT working_copies_program_identity_key UNIQUE (program_id, working_copy_id)
);

ALTER TABLE blockcommander.hall_program_mounts
    ADD COLUMN IF NOT EXISTS working_copy_id uuid;

-- Allocate independently per mount, never per Program. Existing IDs survive reruns.
UPDATE blockcommander.hall_program_mounts
   SET working_copy_id = gen_random_uuid()
 WHERE working_copy_id IS NULL;

INSERT INTO blockcommander.working_copies (working_copy_id, program_id)
SELECT working_copy_id, program_id FROM blockcommander.hall_program_mounts
ON CONFLICT (working_copy_id) DO NOTHING;

ALTER TABLE blockcommander.hall_program_mounts
    ALTER COLUMN working_copy_id SET NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = 'blockcommander.hall_program_mounts'::regclass
           AND conname = 'hall_program_mounts_working_copy_fkey'
    ) THEN
        ALTER TABLE blockcommander.hall_program_mounts
            ADD CONSTRAINT hall_program_mounts_working_copy_fkey
            FOREIGN KEY (program_id, working_copy_id)
            REFERENCES blockcommander.working_copies(program_id, working_copy_id)
            ON DELETE RESTRICT;
    END IF;
END;
$$;

-- Hall deletion cascades only to its existing mount row. WorkingCopy has no Hall FK.
-- No UNIQUE on mounted working_copy_id: future explicit remount policies stay possible.
