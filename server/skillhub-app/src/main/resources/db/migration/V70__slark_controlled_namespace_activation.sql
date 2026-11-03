-- REQ-20261013-0021 / 24050: forward-only controlled activation; V68 remains immutable.
-- No PUBLIC execution grant. Deployment must use separate migration, application and operator roles.
ALTER TABLE public.slark_namespace_binding DROP CONSTRAINT slark_namespace_binding_provision_state_check;
ALTER TABLE public.slark_namespace_binding ADD CONSTRAINT slark_namespace_binding_provision_state_check
    CHECK (provision_state IN ('PREPARED','ACTIVE'));
ALTER TABLE public.slark_namespace_binding ADD COLUMN revision BIGINT NOT NULL DEFAULT 0 CHECK (revision>=0);

CREATE TABLE public.slark_namespace_transition (
    request_id UUID PRIMARY KEY,
    registry_id VARCHAR(200) NOT NULL,
    scope_kind VARCHAR(32) NOT NULL,
    owner_id VARCHAR(200) NOT NULL,
    namespace_id BIGINT NOT NULL REFERENCES public.namespace(id) ON DELETE RESTRICT,
    namespace_slug VARCHAR(200) NOT NULL,
    expected_revision BIGINT NOT NULL CHECK (expected_revision>=0),
    target_state VARCHAR(32) NOT NULL CHECK (target_state IN ('PREPARED','ACTIVE')),
    release_commit TEXT NOT NULL CHECK (release_commit ~ '^[0-9a-f]{40}$'),
    image_digest TEXT NOT NULL CHECK (image_digest ~ '^sha256:[0-9a-f]{64}$'),
    reason TEXT NOT NULL CHECK (length(btrim(reason)) BETWEEN 1 AND 2000),
    operator_name TEXT NOT NULL,
    transaction_id BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(namespace_id,expected_revision)
);
REVOKE ALL ON public.slark_namespace_transition FROM PUBLIC;

CREATE OR REPLACE FUNCTION public.guard_slark_namespace_binding() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE ns public.namespace%ROWTYPE;
BEGIN
    IF TG_OP='UPDATE' AND pg_trigger_depth()>1
       AND (to_jsonb(NEW)-'provision_state'-'revision')=(to_jsonb(OLD)-'provision_state'-'revision')
       AND NEW.revision=OLD.revision+1 AND NEW.provision_state<>OLD.provision_state
       AND EXISTS (SELECT 1 FROM public.slark_namespace_transition t WHERE t.namespace_id=OLD.namespace_id
          AND t.expected_revision=OLD.revision AND t.target_state=NEW.provision_state
          AND t.transaction_id=txid_current()) THEN
        RETURN NEW;
    END IF;
    IF TG_OP<>'INSERT' THEN
        RAISE EXCEPTION 'Slark namespace binding is immutable outside controlled transition' USING ERRCODE='23514';
    END IF;
    SELECT * INTO ns FROM public.namespace WHERE id=NEW.namespace_id FOR UPDATE;
    IF NOT FOUND OR NEW.provision_state<>'PREPARED' OR NEW.revision<>0 OR ns.status<>'FROZEN'
       OR ns.type<>(CASE WHEN NEW.scope_kind='public' THEN 'GLOBAL' ELSE 'TEAM' END) THEN
        RAISE EXCEPTION 'Slark namespace must be prepared and frozen' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END; $$;

CREATE OR REPLACE FUNCTION public.guard_slark_prepared_namespace() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE binding public.slark_namespace_binding%ROWTYPE;
BEGIN
    SELECT * INTO binding FROM public.slark_namespace_binding WHERE namespace_id=OLD.id;
    IF NOT FOUND THEN RETURN NEW; END IF;
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.slug IS DISTINCT FROM OLD.slug OR NEW.type IS DISTINCT FROM OLD.type THEN
        RAISE EXCEPTION 'Slark namespace identity is immutable' USING ERRCODE='23514';
    END IF;
    IF NEW.status IS DISTINCT FROM OLD.status AND NOT (pg_trigger_depth()>1 AND EXISTS (
        SELECT 1 FROM public.slark_namespace_transition t WHERE t.namespace_id=OLD.id
          AND t.transaction_id=txid_current() AND t.expected_revision=binding.revision-1
          AND t.target_state=binding.provision_state
          AND NEW.status=(CASE WHEN t.target_state='ACTIVE' THEN 'ACTIVE' ELSE 'FROZEN' END))) THEN
        RAISE EXCEPTION 'Slark namespace requires controlled transition' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END; $$;

CREATE FUNCTION public.apply_slark_namespace_transition() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE binding public.slark_namespace_binding%ROWTYPE;
DECLARE ns public.namespace%ROWTYPE;
BEGIN
    IF TG_OP<>'INSERT' THEN
        RAISE EXCEPTION 'Slark transition audit is immutable' USING ERRCODE='23514';
    END IF;
    SELECT * INTO ns FROM public.namespace WHERE id=NEW.namespace_id FOR UPDATE;
    IF NOT FOUND OR ns.slug<>NEW.namespace_slug THEN
        RAISE EXCEPTION 'Slark namespace coordinate mismatch' USING ERRCODE='23514';
    END IF;
    SELECT * INTO binding FROM public.slark_namespace_binding WHERE namespace_id=NEW.namespace_id FOR UPDATE;
    IF NOT FOUND OR binding.registry_id<>NEW.registry_id OR binding.scope_kind<>NEW.scope_kind
       OR binding.owner_id<>NEW.owner_id OR binding.revision<>NEW.expected_revision
       OR binding.provision_state=NEW.target_state
       OR ns.status<>(CASE WHEN binding.provision_state='ACTIVE' THEN 'ACTIVE' ELSE 'FROZEN' END)
       OR NEW.transaction_id<>txid_current() OR NEW.operator_name<>session_user THEN
        RAISE EXCEPTION 'Slark namespace transition conflict' USING ERRCODE='23514';
    END IF;
    UPDATE public.slark_namespace_binding SET provision_state=NEW.target_state,revision=revision+1
        WHERE namespace_id=NEW.namespace_id;
    UPDATE public.namespace SET status=CASE WHEN NEW.target_state='ACTIVE' THEN 'ACTIVE' ELSE 'FROZEN' END
        WHERE id=NEW.namespace_id;
    RETURN NEW;
END; $$;
CREATE TRIGGER slark_namespace_transition_apply AFTER INSERT ON public.slark_namespace_transition
    FOR EACH ROW EXECUTE FUNCTION public.apply_slark_namespace_transition();
CREATE TRIGGER slark_namespace_transition_immutable BEFORE UPDATE OR DELETE ON public.slark_namespace_transition
    FOR EACH ROW EXECUTE FUNCTION public.apply_slark_namespace_transition();

-- Deliberately no application HTTP endpoint. Only an explicitly granted operator may invoke this.
CREATE FUNCTION public.slark_transition_namespace(
    registry TEXT, scope TEXT, owner_id TEXT, namespace_id BIGINT, namespace_slug TEXT,
    expected_revision BIGINT, target_state TEXT, request_id TEXT,
    release_commit TEXT, image_digest TEXT, reason TEXT
) RETURNS BIGINT LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public AS $$
DECLARE previous public.slark_namespace_transition%ROWTYPE;
BEGIN
    IF registry IS NULL OR scope IS NULL OR owner_id IS NULL OR namespace_id IS NULL OR namespace_slug IS NULL
       OR expected_revision IS NULL OR target_state IS NULL OR request_id IS NULL
       OR release_commit IS NULL OR image_digest IS NULL OR reason IS NULL THEN
        RAISE EXCEPTION 'Slark transition requires every field' USING ERRCODE='23514';
    END IF;
    PERFORM set_config('lock_timeout','2000ms',true);
    PERFORM set_config('statement_timeout','3000ms',true);
    PERFORM pg_advisory_xact_lock(hashtextextended(namespace_slug,0));
    SELECT * INTO previous FROM public.slark_namespace_transition t WHERE t.request_id=slark_transition_namespace.request_id::uuid;
    IF FOUND THEN
        IF ROW(previous.registry_id,previous.scope_kind,previous.owner_id,previous.namespace_id,previous.namespace_slug,
               previous.expected_revision,previous.target_state,previous.release_commit,previous.image_digest,previous.reason,previous.operator_name)
           IS DISTINCT FROM ROW(registry,scope,owner_id,namespace_id,namespace_slug,expected_revision,target_state,release_commit,image_digest,reason,session_user::text) THEN
            RAISE EXCEPTION 'Slark transition request ID reused with different input' USING ERRCODE='23514';
        END IF;
        RETURN previous.expected_revision+1;
    END IF;
    INSERT INTO public.slark_namespace_transition(request_id,registry_id,scope_kind,owner_id,namespace_id,namespace_slug,
        expected_revision,target_state,release_commit,image_digest,reason,operator_name,transaction_id)
    VALUES (request_id::uuid,registry,scope,owner_id,namespace_id,namespace_slug,expected_revision,target_state,
        release_commit,image_digest,reason,session_user,txid_current());
    RETURN expected_revision+1;
END; $$;
REVOKE ALL ON FUNCTION public.slark_transition_namespace(TEXT,TEXT,TEXT,BIGINT,TEXT,BIGINT,TEXT,TEXT,TEXT,TEXT,TEXT) FROM PUBLIC;
