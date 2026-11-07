-- Desired selection only: no world writes or workspace/content copies.
ALTER TABLE blockcommander.hall_program_mounts
    ADD COLUMN active_stage_id uuid,
    ADD COLUMN selection_revision bigint NOT NULL DEFAULT 0
        CHECK (selection_revision >= 0);

UPDATE blockcommander.hall_program_mounts m
   SET active_stage_id = p.root_stage_id
  FROM blockcommander.programs p WHERE p.program_id = m.program_id;

ALTER TABLE blockcommander.hall_program_mounts
    ALTER COLUMN active_stage_id SET NOT NULL,
    ADD CONSTRAINT hall_mount_working_copy_unique UNIQUE (working_copy_id),
    ADD CONSTRAINT hall_selection_stage_fkey
        FOREIGN KEY (program_id, active_stage_id)
        REFERENCES blockcommander.stages(program_id, stage_id) ON DELETE RESTRICT;
