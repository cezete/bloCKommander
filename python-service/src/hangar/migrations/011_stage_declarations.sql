-- Saved Stage content only. CRUD still mutates the shared in-memory workspace.
ALTER TABLE blockcommander.stages ADD COLUMN declaration_schema_version integer
    NOT NULL DEFAULT 0 CHECK (declaration_schema_version >= 0);

CREATE TABLE blockcommander.stage_declarations (
    declaration_id uuid PRIMARY KEY,
    stage_id uuid NOT NULL REFERENCES blockcommander.stages(stage_id) ON DELETE CASCADE,
    name varchar(64) COLLATE "C" NOT NULL
        CHECK (name ~ '^[A-Za-z_][A-Za-z0-9_]*$'),
    name_key varchar(64) COLLATE "C" GENERATED ALWAYS AS (name) STORED,
    type_key varchar(16) NOT NULL CHECK (type_key IN ('BOOLEAN','INTEGER','STRING')),
    mutability varchar(16) NOT NULL CHECK (mutability IN ('IMMUTABLE','MUTABLE')),
    UNIQUE(stage_id, name_key)
);

-- Protocol handshake, not an authorization boundary against malicious DB owners.
-- A pre-B1 writer does not set this transaction-local marker. Its old DELETE +
-- INSERT + saved_version UPDATE sequence must fail atomically on protected Stages.
CREATE FUNCTION blockcommander.guard_declaration_writer() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE sid uuid; version integer; needs_marker boolean;
BEGIN
    IF TG_TABLE_NAME = 'stages' THEN
        version := OLD.declaration_schema_version;
        needs_marker := version <> 0;
        IF TG_OP = 'UPDATE' THEN
            -- Hall deletion detaches this legacy projection reference only;
            -- it must not turn Stage-owned declarations into Hall-owned data.
            IF NEW.hall_id IS NULL AND OLD.hall_id IS NOT NULL
                AND (to_jsonb(NEW) - 'hall_id') = (to_jsonb(OLD) - 'hall_id') THEN
                RETURN NEW;
            END IF;
            needs_marker := needs_marker OR NEW.declaration_schema_version <> 0;
            IF NEW.declaration_schema_version NOT IN (0,1)
                OR NEW.declaration_schema_version < version THEN
                RAISE EXCEPTION 'Unsupported declaration format or downgrade' USING ERRCODE = '55000';
            END IF;
        END IF;
    ELSE
        IF TG_OP = 'DELETE' THEN sid := OLD.stage_id; ELSE sid := NEW.stage_id; END IF;
        SELECT declaration_schema_version INTO version FROM blockcommander.stages WHERE stage_id = sid;
        needs_marker := TG_TABLE_NAME = 'stage_declarations' OR COALESCE(version,0) <> 0;
        IF TG_OP = 'UPDATE' AND TG_TABLE_NAME = 'stage_declarations' THEN
            IF NEW.declaration_id <> OLD.declaration_id OR NEW.stage_id <> OLD.stage_id THEN
                RAISE EXCEPTION 'Declaration identity and owner are immutable' USING ERRCODE = '55000';
            END IF;
        END IF;
    END IF;
    IF needs_marker AND (COALESCE(current_setting('blockcommander.declaration_writer',true),'') <> '1'
                         OR COALESCE(version,0) NOT IN (0,1)) THEN
        RAISE EXCEPTION 'Declaration-aware Program SAVE required' USING ERRCODE = '55000';
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
END;
$$;

CREATE TRIGGER stage_declaration_writer_guard BEFORE UPDATE OR DELETE ON blockcommander.stages
    FOR EACH ROW EXECUTE FUNCTION blockcommander.guard_declaration_writer();

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['stage_blocks','graph_edges','edge_routes','route_steps',
        'wire_cells','wire_channels','stage_declarations'] LOOP
        EXECUTE format('CREATE TRIGGER declaration_writer_guard BEFORE INSERT OR UPDATE OR DELETE ON blockcommander.%I FOR EACH ROW EXECUTE FUNCTION blockcommander.guard_declaration_writer()',t);
    END LOOP;
END;
$$;

-- The version header is updated at the end of the existing snapshot replacement.
CREATE FUNCTION blockcommander.check_declaration_format() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE sid uuid;
BEGIN
    IF TG_OP = 'DELETE' THEN sid := OLD.stage_id; ELSE sid := NEW.stage_id; END IF;
    IF EXISTS (SELECT 1 FROM blockcommander.stage_declarations d
        JOIN blockcommander.stages s USING(stage_id)
        WHERE d.stage_id = sid AND s.declaration_schema_version <> 1) THEN
        RAISE EXCEPTION 'Declarations require format 1' USING ERRCODE = '55000';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER declaration_format_consistency AFTER INSERT OR UPDATE OR DELETE
    ON blockcommander.stage_declarations DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION blockcommander.check_declaration_format();
