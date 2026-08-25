CREATE TEMPORARY TABLE onestep_legacy_action_code_enum_types ON COMMIT DROP AS
SELECT DISTINCT type_namespace.nspname AS schema_name,
                action_code_type.typname AS type_name
FROM pg_attribute column_definition
JOIN pg_class owning_table
  ON owning_table.oid = column_definition.attrelid
JOIN pg_namespace table_namespace
  ON table_namespace.oid = owning_table.relnamespace
JOIN pg_type action_code_type
  ON action_code_type.oid = column_definition.atttypid
JOIN pg_namespace type_namespace
  ON type_namespace.oid = action_code_type.typnamespace
WHERE table_namespace.nspname = current_schema()
  AND owning_table.relname IN ('share_session', 'share_session_completed_step')
  AND column_definition.attname = 'action_code'
  AND NOT column_definition.attisdropped
  AND action_code_type.typtype = 'e';

DO $$
DECLARE
    target_table text;
    constraint_name text;
BEGIN
    FOREACH target_table IN ARRAY ARRAY['share_session', 'share_session_completed_step']
    LOOP
        IF to_regclass(format('%I.%I', current_schema(), target_table)) IS NULL THEN
            CONTINUE;
        END IF;

        FOR constraint_name IN
            SELECT constraint_definition.conname
            FROM pg_constraint constraint_definition
            JOIN pg_class owning_table
              ON owning_table.oid = constraint_definition.conrelid
            JOIN pg_namespace table_namespace
              ON table_namespace.oid = owning_table.relnamespace
            JOIN pg_attribute constrained_column
              ON constrained_column.attrelid = owning_table.oid
             AND constrained_column.attname = 'action_code'
             AND constrained_column.attnum = ANY(constraint_definition.conkey)
            WHERE table_namespace.nspname = current_schema()
              AND owning_table.relname = target_table
              AND constraint_definition.contype = 'c'
        LOOP
            EXECUTE format('ALTER TABLE %I.%I DROP CONSTRAINT %I',
                           current_schema(), target_table, constraint_name);
        END LOOP;

        IF EXISTS (
            SELECT 1
            FROM information_schema.columns
            WHERE table_schema = current_schema()
              AND table_name = target_table
              AND column_name = 'action_code'
        ) THEN
            EXECUTE format(
                    'ALTER TABLE %I.%I ALTER COLUMN action_code TYPE VARCHAR(32) USING action_code::text',
                    current_schema(), target_table);
        END IF;
    END LOOP;
END
$$;

DO $$
DECLARE
    legacy_type record;
BEGIN
    FOR legacy_type IN SELECT schema_name, type_name FROM onestep_legacy_action_code_enum_types
    LOOP
        BEGIN
            EXECUTE format('DROP TYPE %I.%I', legacy_type.schema_name, legacy_type.type_name);
        EXCEPTION
            WHEN dependent_objects_still_exist THEN
                NULL;
        END;
    END LOOP;
END
$$;
