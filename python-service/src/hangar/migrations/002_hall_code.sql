ALTER TABLE blockcommander.halls ADD COLUMN IF NOT EXISTS hall_code varchar(32);
UPDATE blockcommander.halls SET hall_code = 'hall-' || left(hall_id::text, 8) WHERE hall_code IS NULL;
ALTER TABLE blockcommander.halls ALTER COLUMN hall_code SET NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS halls_owner_code_unique
    ON blockcommander.halls (owner_uuid, lower(hall_code));
