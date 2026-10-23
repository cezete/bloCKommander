ALTER TABLE blockcommander.stages
    ADD COLUMN graph_snapshot_version integer NOT NULL DEFAULT 0
        CHECK (graph_snapshot_version >= 0),
    ADD COLUMN port_catalog_version varchar(64);

ALTER TABLE blockcommander.stage_blocks
    ADD CONSTRAINT stage_blocks_stage_instance_unique
    UNIQUE (stage_id, instance_id);

CREATE TABLE blockcommander.graph_edges (
    edge_id uuid PRIMARY KEY,
    stage_id uuid NOT NULL
        REFERENCES blockcommander.stages(stage_id) ON DELETE CASCADE,
    source_instance_id uuid NOT NULL,
    source_port_key varchar(64) NOT NULL
        CHECK (source_port_key ~ '^[A-Za-z][A-Za-z0-9_]*$'),
    target_instance_id uuid NOT NULL,
    target_port_key varchar(64) NOT NULL
        CHECK (target_port_key ~ '^[A-Za-z][A-Za-z0-9_]*$'),
    edge_kind varchar(32) NOT NULL
        CHECK (edge_kind IN ('CONTROL')),
    CONSTRAINT graph_edges_source_node_fk
        FOREIGN KEY (stage_id, source_instance_id)
        REFERENCES blockcommander.stage_blocks(stage_id, instance_id)
        ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT graph_edges_target_node_fk
        FOREIGN KEY (stage_id, target_instance_id)
        REFERENCES blockcommander.stage_blocks(stage_id, instance_id)
        ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT graph_edges_semantic_unique UNIQUE
        (stage_id, source_instance_id, source_port_key,
         target_instance_id, target_port_key, edge_kind),
    CONSTRAINT graph_edges_stage_edge_unique UNIQUE (stage_id, edge_id)
);

CREATE INDEX graph_edges_source_idx
    ON blockcommander.graph_edges(stage_id, source_instance_id);
CREATE INDEX graph_edges_target_idx
    ON blockcommander.graph_edges(stage_id, target_instance_id);

CREATE TABLE blockcommander.edge_routes (
    route_id uuid PRIMARY KEY,
    stage_id uuid NOT NULL
        REFERENCES blockcommander.stages(stage_id) ON DELETE CASCADE,
    edge_id uuid,
    source_instance_id uuid,
    source_port_key varchar(64),
    target_instance_id uuid,
    target_port_key varchar(64),
    route_status varchar(16) NOT NULL
        CHECK (route_status IN
            ('INCOMPLETE', 'VALID', 'BROKEN', 'AMBIGUOUS', 'ILLEGAL')),
    CONSTRAINT edge_routes_source_pair_check CHECK
        ((source_instance_id IS NULL) = (source_port_key IS NULL)),
    CONSTRAINT edge_routes_target_pair_check CHECK
        ((target_instance_id IS NULL) = (target_port_key IS NULL)),
    CONSTRAINT edge_routes_source_key_check CHECK
        (source_port_key IS NULL OR
         source_port_key ~ '^[A-Za-z][A-Za-z0-9_]*$'),
    CONSTRAINT edge_routes_target_key_check CHECK
        (target_port_key IS NULL OR
         target_port_key ~ '^[A-Za-z][A-Za-z0-9_]*$'),
    CONSTRAINT edge_routes_edge_fk
        FOREIGN KEY (stage_id, edge_id)
        REFERENCES blockcommander.graph_edges(stage_id, edge_id)
        ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT edge_routes_source_node_fk
        FOREIGN KEY (stage_id, source_instance_id)
        REFERENCES blockcommander.stage_blocks(stage_id, instance_id)
        ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT edge_routes_target_node_fk
        FOREIGN KEY (stage_id, target_instance_id)
        REFERENCES blockcommander.stage_blocks(stage_id, instance_id)
        ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT edge_routes_stage_route_unique UNIQUE (stage_id, route_id)
);

CREATE INDEX edge_routes_stage_idx
    ON blockcommander.edge_routes(stage_id);
CREATE INDEX edge_routes_edge_idx
    ON blockcommander.edge_routes(stage_id, edge_id);

CREATE TABLE blockcommander.route_steps (
    route_id uuid NOT NULL,
    stage_id uuid NOT NULL,
    step_index integer NOT NULL CHECK (step_index >= 0),
    rel_x integer NOT NULL,
    rel_y integer NOT NULL,
    rel_z integer NOT NULL,
    channel varchar(8) NOT NULL
        CHECK (channel IN ('SINGLE', 'NS', 'EW')),
    PRIMARY KEY (route_id, step_index),
    CONSTRAINT route_steps_route_fk
        FOREIGN KEY (stage_id, route_id)
        REFERENCES blockcommander.edge_routes(stage_id, route_id)
        ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED
);

CREATE INDEX route_steps_stage_position_idx
    ON blockcommander.route_steps(stage_id, rel_x, rel_y, rel_z);

CREATE TABLE blockcommander.wire_cells (
    stage_id uuid NOT NULL
        REFERENCES blockcommander.stages(stage_id) ON DELETE CASCADE,
    rel_x integer NOT NULL,
    rel_y integer NOT NULL,
    rel_z integer NOT NULL,
    cell_kind varchar(8) NOT NULL
        CHECK (cell_kind IN ('SINGLE', 'CROSS')),
    PRIMARY KEY (stage_id, rel_x, rel_y, rel_z)
);

CREATE TABLE blockcommander.wire_channels (
    stage_id uuid NOT NULL,
    rel_x integer NOT NULL,
    rel_y integer NOT NULL,
    rel_z integer NOT NULL,
    channel varchar(8) NOT NULL,
    connections_mask smallint NOT NULL
        CHECK (connections_mask BETWEEN 0 AND 15),
    PRIMARY KEY (stage_id, rel_x, rel_y, rel_z, channel),
    CONSTRAINT wire_channels_cell_fk
        FOREIGN KEY (stage_id, rel_x, rel_y, rel_z)
        REFERENCES blockcommander.wire_cells(stage_id, rel_x, rel_y, rel_z)
        ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT wire_channels_shape_check CHECK (
        (channel = 'SINGLE' AND bit_count(connections_mask::integer::bit(4)) <= 2)
        OR (channel = 'NS' AND connections_mask = 5)
        OR (channel = 'EW' AND connections_mask = 10)
    )
);

CREATE INDEX wire_channels_stage_idx
    ON blockcommander.wire_channels(stage_id);

CREATE FUNCTION blockcommander.assert_wire_cell_channels(
    checked_stage_id uuid,
    checked_rel_x integer,
    checked_rel_y integer,
    checked_rel_z integer
) RETURNS void
LANGUAGE plpgsql
AS $$
DECLARE
    checked_kind varchar(8);
    actual_channels varchar[];
BEGIN
    SELECT cell_kind
      INTO checked_kind
      FROM blockcommander.wire_cells
     WHERE stage_id = checked_stage_id
       AND rel_x = checked_rel_x
       AND rel_y = checked_rel_y
       AND rel_z = checked_rel_z;

    IF NOT FOUND THEN
        RETURN;
    END IF;

    SELECT COALESCE(array_agg(channel ORDER BY channel), ARRAY[]::varchar[])
      INTO actual_channels
      FROM blockcommander.wire_channels
     WHERE stage_id = checked_stage_id
       AND rel_x = checked_rel_x
       AND rel_y = checked_rel_y
       AND rel_z = checked_rel_z;

    IF checked_kind = 'SINGLE'
       AND actual_channels <> ARRAY['SINGLE']::varchar[] THEN
        RAISE EXCEPTION 'SINGLE wire cell must have exactly the SINGLE channel'
            USING ERRCODE = '23514';
    END IF;

    IF checked_kind = 'CROSS'
       AND actual_channels <> ARRAY['EW', 'NS']::varchar[] THEN
        RAISE EXCEPTION 'CROSS wire cell must have exactly the NS and EW channels'
            USING ERRCODE = '23514';
    END IF;
END;
$$;

CREATE FUNCTION blockcommander.check_wire_cell_channels_trigger()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        PERFORM blockcommander.assert_wire_cell_channels(
            NEW.stage_id, NEW.rel_x, NEW.rel_y, NEW.rel_z
        );
    ELSIF TG_OP = 'DELETE' THEN
        PERFORM blockcommander.assert_wire_cell_channels(
            OLD.stage_id, OLD.rel_x, OLD.rel_y, OLD.rel_z
        );
    ELSE
        PERFORM blockcommander.assert_wire_cell_channels(
            OLD.stage_id, OLD.rel_x, OLD.rel_y, OLD.rel_z
        );
        PERFORM blockcommander.assert_wire_cell_channels(
            NEW.stage_id, NEW.rel_x, NEW.rel_y, NEW.rel_z
        );
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER wire_cells_channel_cardinality
AFTER INSERT OR UPDATE OR DELETE ON blockcommander.wire_cells
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION blockcommander.check_wire_cell_channels_trigger();

CREATE CONSTRAINT TRIGGER wire_channels_cell_cardinality
AFTER INSERT OR UPDATE OR DELETE ON blockcommander.wire_channels
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION blockcommander.check_wire_cell_channels_trigger();
