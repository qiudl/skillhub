# REQ-20261013-0021 / 24050: controlled namespace activation

V70 adds an operator-only PostgreSQL operation. It does not activate existing
namespaces, add members, enable Slark features or change public/native download
isolation. V68 and V69 remain unchanged. Distribution/catalog still require the
exact ACTIVE binding and their existing live Slark authorization and SAFE/hash
checks. Activation is not membership or an execution grant.

Use different database identities for migration ownership, application runtime
and operations. Application runtime must not own these tables/functions, inherit
the migration/operator role, have DDL or audit-table write privileges, or execute
`public.slark_transition_namespace`. The migration owner can inherently override
its own protections; running the application as that owner is unsupported for
controlled operation. V70 revokes PUBLIC execution and audit-table access; review
existing explicit/default grants when deploying. No operator role or grant is
created automatically. A deployment owner explicitly grants EXECUTE on the exact
11-argument function only to its dedicated operator identity. Revoke the grant
after the approved operation. Never grant audit writes or trigger/DDL privileges.

Before activation, review the exact source commit, built image digest and both
services' access checks, and retain the approval and acceptance evidence linked
to 24050. Use a dedicated operator connection with client query timeout and
`PGOPTIONS='-c statement_timeout=3000ms -c lock_timeout=2000ms'`. Supply all of:

- registry ID, scope kind, owner ID, namespace ID **as bigint**, canonical slug;
- expected revision (initially 0), target state ACTIVE or PREPARED;
- a fresh UUID request ID, reviewed 40-hex source commit, `sha256:` image digest;
- a specific change/rollback reason referencing REQ-20261013-0021.

Call `public.slark_transition_namespace` with bound parameters in that order.
Never interpolate untrusted values into SQL. Namespace coordinates come from the
existing provisioning response; do not construct/adopt a different namespace.
The function returns the committed transition revision. Query the exact binding,
namespace status and request's audit event using the deployment owner's read
connection to confirm the result. The event records the database `session_user`
(no caller-supplied actor), source/image, reason and transaction/time.

A transaction locks and checks the exact coordinate and expected revision, then
atomically appends an event and changes binding/namespace to ACTIVE/ACTIVE or
PREPARED/FROZEN. Failure leaves no event or partial activation. Events cannot be
updated/deleted through ordinary DML. Direct binding edits and namespace status
or identity edits remain rejected. Other namespaces are unaffected. A duplicate
UUID with identical fields and operator returns its original revision without
mutating current state; different input with the same UUID is a conflict. An old
activation retry after a stop therefore does not reactivate the namespace.

For rollback, use a **new** UUID, the current revision and target PREPARED with
the same reviewed artifact identifiers and an explicit rollback reason. This
freezes new catalog/distribution access; it does not assert cancellation of bytes
already returned or execution already dispatched. Those retain the Slark lease,
revocation and unknown-outcome controls. Keep the migration and immutable audit
history; do not downgrade Flyway, delete events, disable triggers or edit V68.

Local automated acceptance is not production approval or a complete real Slark
login/Host pilot. The formal deployment must still validate role separation,
immutable artifact provenance, full-chain positive/negative cases and exact-head
CI before any separately controlled production activation.
