-- C1 is a saved-domain extension, not an execution or publication contract.
ALTER TABLE blockcommander.stages DROP CONSTRAINT stages_stage_kind_check;
ALTER TABLE blockcommander.stages ADD CONSTRAINT stages_stage_kind_check
    CHECK (stage_kind IN ('ROOT','PROGRAM_BODY','VALUE_BODY'));
ALTER TABLE blockcommander.stages ADD COLUMN value_schema_version integer NOT NULL DEFAULT 0
    CHECK (value_schema_version >= 0);
ALTER TABLE blockcommander.stage_blocks ADD COLUMN semantic_config jsonb NOT NULL DEFAULT '{}'
    CHECK (jsonb_typeof(semantic_config) = 'object');
ALTER TABLE blockcommander.graph_edges DROP CONSTRAINT graph_edges_edge_kind_check;
ALTER TABLE blockcommander.graph_edges ADD CONSTRAINT graph_edges_edge_kind_check
    CHECK (edge_kind IN ('CONTROL','VALUE'));

ALTER TABLE blockcommander.program_stage_links DROP CONSTRAINT program_stage_links_child_fkey;
ALTER TABLE blockcommander.program_stage_links DROP COLUMN child_stage_kind;
ALTER TABLE blockcommander.program_stage_links ADD COLUMN owner_role varchar(32) NOT NULL DEFAULT 'PROGRAM_BODY'
    CHECK (owner_role IN ('PROGRAM_BODY','LET_RHS'));
ALTER TABLE blockcommander.program_stage_links ADD COLUMN child_stage_kind varchar(32)
    GENERATED ALWAYS AS (CASE owner_role WHEN 'LET_RHS' THEN 'VALUE_BODY' ELSE 'PROGRAM_BODY' END) STORED;
ALTER TABLE blockcommander.program_stage_links ADD CONSTRAINT program_stage_links_child_fkey
    FOREIGN KEY (program_id, child_stage_id, child_stage_kind)
    REFERENCES blockcommander.stages(program_id, stage_id, stage_kind)
    ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED;

INSERT INTO blockcommander.program_block_types
    (type_key,display_name,material_name,default_label,default_description,display_definition)
SELECT name,name,'WHITE_GLAZED_TERRACOTTA',name,NULL,'{}'::jsonb
FROM unnest(ARRAY['LET','LITERAL','REF','OPERATOR','RESULT']) AS name
ON CONFLICT (type_key) DO NOTHING;

-- Transaction-local writer protocol. Protect all content and the hierarchy,
-- including an empty VALUE_BODY. Old delete/reinsert writers fail atomically.
CREATE FUNCTION blockcommander.guard_value_writer() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE sid uuid; pid uuid; version integer; protected boolean := false;
BEGIN
    IF TG_TABLE_NAME = 'stages' THEN
        IF TG_OP = 'INSERT' THEN
            version := NEW.value_schema_version;
            protected := version <> 0 OR NEW.stage_kind = 'VALUE_BODY';
        ELSE
            version := OLD.value_schema_version;
            protected := version <> 0 OR OLD.stage_kind = 'VALUE_BODY';
            IF TG_OP = 'UPDATE' THEN
                IF NEW.hall_id IS NULL AND OLD.hall_id IS NOT NULL
                    AND (to_jsonb(NEW)-'hall_id') = (to_jsonb(OLD)-'hall_id') THEN RETURN NEW; END IF;
                protected := protected OR NEW.value_schema_version <> 0 OR NEW.stage_kind = 'VALUE_BODY';
                IF NEW.value_schema_version NOT IN (0,1) OR NEW.value_schema_version < version THEN
                    RAISE EXCEPTION 'Unsupported VALUE format or downgrade' USING ERRCODE='55000';
                END IF;
            END IF;
        END IF;
    ELSIF TG_TABLE_NAME IN ('program_stage_links','programs') THEN
        IF TG_OP = 'DELETE' THEN pid := OLD.program_id; ELSE pid := NEW.program_id; END IF;
        SELECT EXISTS(SELECT 1 FROM blockcommander.stages WHERE program_id=pid
            AND (value_schema_version <> 0 OR stage_kind='VALUE_BODY')) INTO protected;
        IF TG_TABLE_NAME='program_stage_links' AND TG_OP <> 'DELETE' THEN
            protected := protected OR NEW.owner_role='LET_RHS';
        END IF;
        version := 1;
    ELSE
        IF TG_OP = 'DELETE' THEN sid := OLD.stage_id; ELSE sid := NEW.stage_id; END IF;
        SELECT value_schema_version, (value_schema_version <> 0 OR stage_kind='VALUE_BODY')
            INTO version, protected FROM blockcommander.stages WHERE stage_id=sid;
        IF TG_TABLE_NAME='stage_blocks' AND TG_OP <> 'DELETE' THEN
            protected := protected OR NEW.semantic_config <> '{}'::jsonb
                OR NEW.type_key IN ('LET','LITERAL','REF','OPERATOR','RESULT');
        END IF;
        IF TG_TABLE_NAME='graph_edges' AND TG_OP <> 'DELETE' THEN
            protected := protected OR NEW.edge_kind='VALUE';
        END IF;
    END IF;
    IF protected AND (COALESCE(version,0) NOT IN (0,1)
        OR COALESCE(current_setting('blockcommander.value_writer',true),'') <> '1') THEN
        RAISE EXCEPTION 'VALUE-aware Program SAVE required' USING ERRCODE='55000';
    END IF;
    IF TG_OP='DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
END; $$;

DO $$ DECLARE t text; BEGIN
    FOREACH t IN ARRAY ARRAY['stages','programs','program_stage_links','stage_blocks','graph_edges',
        'edge_routes','route_steps','wire_cells','wire_channels','stage_declarations'] LOOP
        EXECUTE format('CREATE TRIGGER value_writer_guard BEFORE INSERT OR UPDATE OR DELETE ON blockcommander.%I FOR EACH ROW EXECUTE FUNCTION blockcommander.guard_value_writer()',t);
    END LOOP;
END; $$;

-- Broken declaration UUIDs intentionally have no declaration FK: they are drafts.
CREATE FUNCTION blockcommander.check_value_format() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE sid uuid;
BEGIN
    IF TG_OP='DELETE' THEN sid := OLD.stage_id; ELSE sid := NEW.stage_id; END IF;
    IF EXISTS(SELECT 1 FROM blockcommander.stages s WHERE s.stage_id=sid AND s.value_schema_version <> 1
        AND (s.stage_kind='VALUE_BODY' OR EXISTS(SELECT 1 FROM blockcommander.stage_blocks b
            WHERE b.stage_id=sid AND (b.semantic_config <> '{}'::jsonb OR b.type_key IN ('LET','LITERAL','REF','OPERATOR','RESULT')))
            OR EXISTS(SELECT 1 FROM blockcommander.graph_edges e WHERE e.stage_id=sid AND e.edge_kind='VALUE'))) THEN
        RAISE EXCEPTION 'VALUE content requires format 1' USING ERRCODE='55000';
    END IF;
    RETURN NULL;
END; $$;
DO $$ DECLARE t text; BEGIN
    FOREACH t IN ARRAY ARRAY['stage_blocks','graph_edges'] LOOP
        EXECUTE format('CREATE CONSTRAINT TRIGGER value_format_consistency AFTER INSERT OR UPDATE OR DELETE ON blockcommander.%I DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION blockcommander.check_value_format()',t);
    END LOOP;
END; $$;
CREATE CONSTRAINT TRIGGER value_format_consistency AFTER INSERT OR UPDATE
    ON blockcommander.stages DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW WHEN (NEW.value_schema_version <> 0 OR NEW.stage_kind='VALUE_BODY')
    EXECUTE FUNCTION blockcommander.check_value_format();
