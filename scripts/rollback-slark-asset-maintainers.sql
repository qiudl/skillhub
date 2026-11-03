-- REQ-20261013-0021 M1-10: offline rollback only, after stopping writers.
-- Flyway history repair is an explicit operator action; never run this automatically.
BEGIN;
LOCK TABLE slark_asset_maintainer,slark_asset_maintainer_event IN ACCESS EXCLUSIVE MODE;
DO $$ BEGIN
 IF EXISTS(SELECT 1 FROM slark_asset_maintainer) OR EXISTS(SELECT 1 FROM slark_asset_maintainer_event) THEN
  RAISE EXCEPTION 'Refuse rollback with maintainer or audit data';
 END IF;
END $$;
DROP TABLE slark_asset_maintainer;
DROP TABLE slark_asset_maintainer_event;
DROP FUNCTION guard_slark_asset_maintainer();
DROP FUNCTION audit_slark_asset_maintainer();
DROP FUNCTION guard_slark_asset_maintainer_event();
COMMIT;
