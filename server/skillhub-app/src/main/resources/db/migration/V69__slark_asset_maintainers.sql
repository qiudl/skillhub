-- REQ-20261013-0021 M1-10: asset-specific rights, never inferred from namespace roles.
CREATE TABLE slark_asset_maintainer (
    skill_id BIGINT NOT NULL REFERENCES skill(id) ON DELETE CASCADE,
    namespace_id BIGINT NOT NULL REFERENCES slark_namespace_binding(namespace_id) ON DELETE RESTRICT,
    subject VARCHAR(200) NOT NULL CHECK (length(subject)>0),
    local_user_id VARCHAR(128) NOT NULL REFERENCES user_account(id) ON DELETE RESTRICT,
    active BOOLEAN NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision>=0),
    actor_subject VARCHAR(200) NOT NULL CHECK (length(actor_subject)>0),
    actor_local_user_id VARCHAR(128) NOT NULL REFERENCES user_account(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(skill_id,subject),
    UNIQUE(skill_id,local_user_id)
);
CREATE INDEX slark_asset_maintainer_namespace_idx ON slark_asset_maintainer(namespace_id);

CREATE TABLE slark_asset_maintainer_event (
    id BIGSERIAL PRIMARY KEY,
    skill_id BIGINT NOT NULL,
    namespace_id BIGINT NOT NULL,
    subject VARCHAR(200) NOT NULL,
    local_user_id VARCHAR(128) NOT NULL,
    active BOOLEAN NOT NULL,
    revision BIGINT NOT NULL,
    actor_subject VARCHAR(200) NOT NULL,
    actor_local_user_id VARCHAR(128) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(skill_id,subject,revision)
);

CREATE FUNCTION guard_slark_asset_maintainer() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN
        IF NOT EXISTS(SELECT 1 FROM skill WHERE id=OLD.skill_id) THEN RETURN OLD; END IF;
        RAISE EXCEPTION 'Slark maintainer must be revoked, not deleted' USING ERRCODE='23514';
    END IF;
    IF TG_OP='UPDATE' AND (NEW.skill_id IS DISTINCT FROM OLD.skill_id OR
       NEW.namespace_id IS DISTINCT FROM OLD.namespace_id OR NEW.subject IS DISTINCT FROM OLD.subject OR
       NEW.local_user_id IS DISTINCT FROM OLD.local_user_id OR NEW.created_at IS DISTINCT FROM OLD.created_at OR
       NEW.revision<>OLD.revision+1) THEN
        RAISE EXCEPTION 'Slark maintainer identity is immutable' USING ERRCODE='23514';
    END IF;
    IF TG_OP='INSERT' AND NEW.revision<>0 THEN
        RAISE EXCEPTION 'Slark maintainer starts at revision zero' USING ERRCODE='23514';
    END IF;
    IF NOT EXISTS(SELECT 1 FROM skill WHERE id=NEW.skill_id AND namespace_id=NEW.namespace_id) OR
       NOT EXISTS(SELECT 1 FROM identity_binding WHERE provider_code='slark' AND subject=NEW.subject AND user_id=NEW.local_user_id) OR
       NOT EXISTS(SELECT 1 FROM identity_binding WHERE provider_code='slark' AND subject=NEW.actor_subject AND user_id=NEW.actor_local_user_id) THEN
        RAISE EXCEPTION 'Slark maintainer binding mismatch' USING ERRCODE='23514';
    END IF;
    NEW.updated_at=clock_timestamp();
    RETURN NEW;
END; $$;
CREATE TRIGGER slark_asset_maintainer_guard BEFORE INSERT OR UPDATE OR DELETE ON slark_asset_maintainer
FOR EACH ROW EXECUTE FUNCTION guard_slark_asset_maintainer();

CREATE FUNCTION audit_slark_asset_maintainer() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO slark_asset_maintainer_event(skill_id,namespace_id,subject,local_user_id,active,revision,actor_subject,actor_local_user_id)
    VALUES(NEW.skill_id,NEW.namespace_id,NEW.subject,NEW.local_user_id,NEW.active,NEW.revision,NEW.actor_subject,NEW.actor_local_user_id);
    RETURN NEW;
END; $$;
CREATE TRIGGER slark_asset_maintainer_audit AFTER INSERT OR UPDATE ON slark_asset_maintainer
FOR EACH ROW EXECUTE FUNCTION audit_slark_asset_maintainer();

CREATE FUNCTION guard_slark_asset_maintainer_event() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Slark maintainer audit is append only' USING ERRCODE='23514';
END; $$;
CREATE TRIGGER slark_asset_maintainer_event_guard BEFORE UPDATE OR DELETE ON slark_asset_maintainer_event
FOR EACH ROW EXECUTE FUNCTION guard_slark_asset_maintainer_event();
