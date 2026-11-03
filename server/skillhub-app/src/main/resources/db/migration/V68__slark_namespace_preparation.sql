-- REQ-20261013-0021 M1-06: operated-instance coordinates, initially frozen and without members.
CREATE TABLE slark_namespace_binding (
    registry_id VARCHAR(200) NOT NULL CHECK (length(registry_id)>0),
    scope_kind VARCHAR(32) NOT NULL CHECK (scope_kind IN ('public','organization','project','personal')),
    owner_id VARCHAR(200) NOT NULL CHECK (length(owner_id)>0),
    namespace_id BIGINT NOT NULL UNIQUE CHECK (namespace_id>0) REFERENCES namespace(id) ON DELETE RESTRICT,
    provision_state VARCHAR(32) NOT NULL DEFAULT 'PREPARED' CHECK (provision_state='PREPARED'),
    provisioned_by_client VARCHAR(100) NOT NULL CHECK (length(provisioned_by_client)>0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(registry_id,scope_kind,owner_id),
    CHECK(scope_kind<>'public' OR owner_id='public')
);

CREATE FUNCTION guard_slark_namespace_binding() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE ns namespace%ROWTYPE;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'Slark namespace binding is immutable' USING ERRCODE='23514';
    END IF;
    SELECT * INTO ns FROM namespace WHERE id=NEW.namespace_id FOR UPDATE;
    IF NOT FOUND OR ns.status<>'FROZEN' OR ns.type<>(CASE WHEN NEW.scope_kind='public' THEN 'GLOBAL' ELSE 'TEAM' END) THEN
        RAISE EXCEPTION 'Slark namespace must be prepared and frozen' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER slark_namespace_binding_guard BEFORE INSERT OR UPDATE OR DELETE ON slark_namespace_binding
FOR EACH ROW EXECUTE FUNCTION guard_slark_namespace_binding();

-- Activation requires a later reviewed migration/operation after both services enforce asset permissions.
CREATE FUNCTION guard_slark_prepared_namespace() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM slark_namespace_binding WHERE namespace_id=OLD.id) AND
       (NEW.id IS DISTINCT FROM OLD.id OR NEW.slug IS DISTINCT FROM OLD.slug OR
        NEW.type IS DISTINCT FROM OLD.type OR NEW.status<>'FROZEN') THEN
        RAISE EXCEPTION 'Slark prepared namespace cannot be reassigned or activated' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER slark_prepared_namespace_guard BEFORE UPDATE ON namespace
FOR EACH ROW EXECUTE FUNCTION guard_slark_prepared_namespace();
