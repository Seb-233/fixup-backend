-- Run only against an isolated PostgreSQL 17 database migrated through V42.
-- The fixture and helpers are rolled back, including every attempted application write.
BEGIN;

CREATE FUNCTION pg_temp.assert_check(ok boolean, label text) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    IF ok IS DISTINCT FROM true THEN RAISE EXCEPTION 'FAIL: %', label; END IF;
    RAISE NOTICE 'PASS: %', label;
END $$;
CREATE FUNCTION pg_temp.expect_denied(property_id uuid, owner_id uuid, label text)
RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    PERFORM public.app_property_owner_has_outstanding_debt(property_id, owner_id);
    RAISE EXCEPTION 'FAIL: authorized unexpectedly: %', label;
EXCEPTION WHEN insufficient_privilege THEN
    IF SQLERRM <> 'Property debt check is not authorized' THEN RAISE; END IF;
    RAISE NOTICE 'PASS: %', label;
END $$;

INSERT INTO public.users(id,auth0_subject,display_name,status,created_at,updated_at)
SELECT md5('i08-' || n)::uuid,'i08|' || n,n,'ACTIVE',now(),now()
FROM unnest(ARRAY['owner-debt','owner-clear','tenant-debt','tenant-clear','manager-debt','manager-clear',
                  'stranger','fixer','admin']) n;
INSERT INTO public.user_roles(user_id,role) VALUES
(md5('i08-owner-debt')::uuid,'OWNER'),(md5('i08-owner-clear')::uuid,'OWNER'),
(md5('i08-tenant-debt')::uuid,'TENANT'),(md5('i08-tenant-clear')::uuid,'TENANT'),
(md5('i08-manager-debt')::uuid,'REAL_ESTATE_MANAGER'),(md5('i08-manager-clear')::uuid,'REAL_ESTATE_MANAGER'),
(md5('i08-stranger')::uuid,'OWNER'),(md5('i08-fixer')::uuid,'FIXER'),(md5('i08-admin')::uuid,'PLATFORM_ADMIN');
INSERT INTO public.properties(id,owner_user_id,manager_user_id,name,address,city,area_m2,status,created_at,updated_at)
SELECT md5('i08-' || p)::uuid,md5('i08-' || o)::uuid,CASE WHEN m IS NULL THEN NULL ELSE md5('i08-' || m)::uuid END,
       p,'Synthetic address','Bogota',80,'PUBLISHED',now(),now()
FROM (VALUES ('property-debt','owner-debt','manager-debt'),('property-clear','owner-clear','manager-clear'),
             ('debt-origin','owner-debt',NULL)) x(p,o,m);
INSERT INTO public.rental_contracts(id,property_id,owner_user_id,tenant_user_id,start_date,end_date,
                                  monthly_rent_amount,deposit_amount,payment_day_of_month,status,created_at,updated_at)
SELECT md5('i08-contract-' || n)::uuid,md5('i08-property-' || n)::uuid,md5('i08-owner-' || n)::uuid,
       md5('i08-tenant-' || n)::uuid,(now() AT TIME ZONE 'America/Bogota')::date - 30,
       (now() AT TIME ZONE 'America/Bogota')::date + 30,1000000,0,5,'ACTIVE',now(),now()
FROM unnest(ARRAY['debt','clear']) n;
-- Administrative reconstruction of a pre-V41 CASH obligation, before switching to fixup_app.
-- Transaction-local fixture setting only; all authorization assertions below run with origin triggers.
SET LOCAL session_replication_role = replica;
INSERT INTO public.repair_requests(id,property_id,owner_user_id,requested_by_user_id,specialty,title,description,
                                  status,assigned_fixer_user_id,payment_method,legacy_cash,created_at,updated_at)
VALUES(md5('i08-request')::uuid,md5('i08-debt-origin')::uuid,md5('i08-owner-debt')::uuid,md5('i08-owner-debt')::uuid,
       'PLUMBING','Synthetic','Debt on another property of the same owner','COMPLETED',md5('i08-fixer')::uuid,'CASH',true,TIMESTAMPTZ '2020-01-01 00:00:00Z',now());
SET LOCAL session_replication_role = origin;
INSERT INTO public.quotations(id,request_id,fixer_user_id,amount,estimated_days,status,created_at,updated_at)
VALUES(md5('i08-quote')::uuid,md5('i08-request')::uuid,md5('i08-fixer')::uuid,1000,1,'ACCEPTED',now(),now());
INSERT INTO public.owner_debts(id,owner_user_id,fixer_user_id,request_id,quotation_id,amount,status,created_at)
VALUES(md5('i08-debt')::uuid,md5('i08-owner-debt')::uuid,md5('i08-fixer')::uuid,md5('i08-request')::uuid,
       md5('i08-quote')::uuid,1000,'OUTSTANDING',now());

SELECT pg_temp.assert_check((SELECT NOT (rolcanlogin OR rolinherit OR rolsuper OR rolcreatedb OR rolcreaterole
    OR rolreplication OR rolbypassrls) FROM pg_roles WHERE rolname='fixup_property_debt_reader'), 'restricted role flags');
SELECT pg_temp.assert_check(NOT EXISTS (SELECT 1 FROM pg_auth_members m JOIN pg_roles r ON r.oid=m.roleid OR r.oid=m.member
    WHERE r.rolname='fixup_property_debt_reader'), 'role has no members or inherited memberships');
SELECT pg_temp.assert_check(NOT has_schema_privilege('fixup_property_debt_reader','public','CREATE'), 'role cannot create schema objects');
SELECT pg_temp.assert_check(NOT has_column_privilege('fixup_property_debt_reader','public.owner_debts','amount','SELECT')
    AND NOT has_column_privilege('fixup_property_debt_reader','public.owner_debts','id','SELECT'), 'role cannot select debt identifiers or amounts');
SELECT pg_temp.assert_check(NOT has_table_privilege('fixup_property_debt_reader','public.owner_debts','INSERT,UPDATE,DELETE'), 'role has no debt DML');
SELECT pg_temp.assert_check((SELECT prosecdef AND NOT proleakproof AND prorettype='boolean'::regtype
    AND proowner='fixup_property_debt_reader'::regrole AND proconfig @> ARRAY['search_path=pg_catalog, pg_temp','row_security=on']
    FROM pg_proc WHERE oid='public.app_property_owner_has_outstanding_debt(uuid,uuid)'::regprocedure), 'boolean function owner and fixed configuration');
SELECT pg_temp.assert_check(NOT EXISTS (SELECT 1 FROM pg_proc p, LATERAL aclexplode(p.proacl) a
    WHERE p.oid='public.app_property_owner_has_outstanding_debt(uuid,uuid)'::regprocedure AND a.grantee=0), 'no PUBLIC execute');
SELECT pg_temp.assert_check(has_function_privilege('fixup_app','public.app_property_owner_has_outstanding_debt(uuid,uuid)','EXECUTE'), 'application execute granted');
SELECT pg_temp.assert_check((SELECT relrowsecurity AND relforcerowsecurity FROM pg_class WHERE oid='public.owner_debts'::regclass), 'debt RLS remains forced');
SELECT pg_temp.assert_check((SELECT roles=ARRAY['fixup_property_debt_reader']::name[] AND cmd='SELECT' FROM pg_policies
    WHERE schemaname='public' AND tablename='owner_debts' AND policyname='owner_debts_property_check'), 'new policy limited to internal role and SELECT');

SET LOCAL ROLE fixup_app;
SELECT set_config('app.current_user_id',md5('i08-owner-clear')::uuid::text,true),set_config('app.current_user_roles','OWNER',true);
SELECT pg_temp.assert_check(NOT public.app_property_owner_has_outstanding_debt(md5('i08-property-clear')::uuid,md5('i08-owner-clear')::uuid), 'owner without debt');
SELECT set_config('app.current_user_id',md5('i08-owner-debt')::uuid::text,true);
SELECT pg_temp.assert_check(public.app_property_owner_has_outstanding_debt(md5('i08-property-debt')::uuid,md5('i08-owner-debt')::uuid), 'owner with debt on another property');
SELECT pg_temp.assert_check((SELECT count(*)=1 FROM public.owner_debts),'owner retains direct debt access');
SELECT set_config('app.current_user_id',md5('i08-tenant-clear')::uuid::text,true),set_config('app.current_user_roles','TENANT',true);
SELECT pg_temp.assert_check(NOT public.app_property_owner_has_outstanding_debt(md5('i08-property-clear')::uuid,md5('i08-owner-clear')::uuid), 'tenant without owner debt');
SELECT set_config('app.current_user_id',md5('i08-tenant-debt')::uuid::text,true);
SELECT pg_temp.assert_check((SELECT count(*)=0 FROM public.owner_debts),'tenant cannot see debt rows');
SELECT pg_temp.assert_check(public.app_property_owner_has_outstanding_debt(md5('i08-property-debt')::uuid,md5('i08-owner-debt')::uuid), 'tenant sees boolean true despite RLS-hidden owner debt');
SELECT pg_temp.expect_denied(md5('i08-debt-origin')::uuid,md5('i08-owner-debt')::uuid,'tenant cannot probe another property even of same owner');
SELECT pg_temp.expect_denied(md5('i08-property-debt')::uuid,md5('i08-owner-clear')::uuid,'wrong expected owner rejected');
SELECT pg_temp.expect_denied(NULL,md5('i08-owner-debt')::uuid,'null property rejected');
SELECT pg_temp.expect_denied(md5('i08-property-debt')::uuid,NULL,'null owner rejected');
SELECT pg_temp.expect_denied(md5('i08-missing')::uuid,md5('i08-owner-debt')::uuid,'missing property rejected');

UPDATE public.owner_debts SET amount=99999 WHERE id=md5('i08-debt')::uuid;
DELETE FROM public.owner_debts WHERE id=md5('i08-debt')::uuid;
DO $$ BEGIN
    BEGIN
        INSERT INTO public.owner_debts(id,owner_user_id,fixer_user_id,request_id,quotation_id,amount,status,created_at)
        VALUES(md5('i08-forbidden')::uuid,md5('i08-owner-debt')::uuid,md5('i08-fixer')::uuid,md5('i08-request')::uuid,
               md5('i08-quote')::uuid,100,'OUTSTANDING',now());
        RAISE EXCEPTION 'FAIL: tenant debt insert allowed';
    EXCEPTION WHEN insufficient_privilege THEN RAISE NOTICE 'PASS: tenant debt insert denied'; END;
END $$;
-- SET ROLE permission is based on session_user. Use a genuinely unprivileged session identity
-- for this test, not the fixture superuser merely downgraded with SET LOCAL ROLE (V10's model).
RESET ROLE;
SET SESSION AUTHORIZATION fixup_app;
DO $$ BEGIN
    BEGIN
        SET LOCAL ROLE fixup_property_debt_reader;
        RAISE EXCEPTION 'FAIL: application assumed internal role';
    EXCEPTION WHEN insufficient_privilege THEN RAISE NOTICE 'PASS: unprivileged session cannot assume internal role'; END;
END $$;
RESET SESSION AUTHORIZATION;
SET LOCAL ROLE fixup_app;
SELECT pg_temp.assert_check(current_user='fixup_app','definer role does not leak to caller');

SELECT set_config('app.current_user_id',md5('i08-manager-clear')::uuid::text,true),set_config('app.current_user_roles','REAL_ESTATE_MANAGER',true);
SELECT pg_temp.assert_check(NOT public.app_property_owner_has_outstanding_debt(md5('i08-property-clear')::uuid,md5('i08-owner-clear')::uuid), 'manager without owner debt');
SELECT set_config('app.current_user_id',md5('i08-manager-debt')::uuid::text,true);
SELECT pg_temp.assert_check((SELECT count(*)=0 FROM public.owner_debts),'manager cannot see debt rows');
SELECT pg_temp.assert_check(public.app_property_owner_has_outstanding_debt(md5('i08-property-debt')::uuid,md5('i08-owner-debt')::uuid), 'manager sees boolean true despite RLS-hidden owner debt');
SELECT pg_temp.expect_denied(md5('i08-property-clear')::uuid,md5('i08-owner-clear')::uuid,'unassigned manager rejected');
SELECT set_config('app.current_user_id',md5('i08-stranger')::uuid::text,true),set_config('app.current_user_roles','OWNER',true);
SELECT pg_temp.assert_check((SELECT count(*)=1 FROM public.properties WHERE id=md5('i08-property-debt')::uuid),'catalog visibility alone exists');
SELECT pg_temp.expect_denied(md5('i08-property-debt')::uuid,md5('i08-owner-debt')::uuid,'catalog visibility grants no debt check');
SELECT pg_temp.assert_check((SELECT count(*)=0 FROM public.owner_debts),'stranger debt visibility unchanged');
SELECT set_config('app.current_user_id',md5('i08-fixer')::uuid::text,true),set_config('app.current_user_roles','FIXER',true);
SELECT pg_temp.assert_check((SELECT count(*)=1 FROM public.owner_debts),'fixer retains existing debt access');
SELECT pg_temp.expect_denied(md5('i08-property-debt')::uuid,md5('i08-owner-debt')::uuid,'assigned debt fixer is not a reporter');
SELECT set_config('app.current_user_id',md5('i08-admin')::uuid::text,true),set_config('app.current_user_roles','PLATFORM_ADMIN',true);
SELECT pg_temp.assert_check((SELECT count(*)=1 FROM public.owner_debts),'platform admin retains existing debt access');
SELECT pg_temp.expect_denied(md5('i08-property-debt')::uuid,md5('i08-owner-debt')::uuid,'platform admin has no implicit reporter relationship');
SELECT set_config('app.current_user_id','',true);
SELECT pg_temp.expect_denied(md5('i08-property-debt')::uuid,md5('i08-owner-debt')::uuid,'missing session actor rejected');

RESET ROLE;
SELECT pg_temp.assert_check((SELECT amount=1000 FROM public.owner_debts WHERE id=md5('i08-debt')::uuid),'third-party debt update/delete had no effect');
UPDATE public.rental_contracts SET end_date=(now() AT TIME ZONE 'America/Bogota')::date-1 WHERE id=md5('i08-contract-debt')::uuid;
SET LOCAL ROLE fixup_app;
SELECT set_config('app.current_user_id',md5('i08-tenant-debt')::uuid::text,true),set_config('app.current_user_roles','TENANT',true);
SELECT pg_temp.expect_denied(md5('i08-property-debt')::uuid,md5('i08-owner-debt')::uuid,'expired date despite ACTIVE status rejected');
RESET ROLE;
UPDATE public.rental_contracts SET start_date=(now() AT TIME ZONE 'America/Bogota')::date+1,
    end_date=(now() AT TIME ZONE 'America/Bogota')::date+30 WHERE id=md5('i08-contract-debt')::uuid;
SET LOCAL ROLE fixup_app;
SELECT pg_temp.expect_denied(md5('i08-property-debt')::uuid,md5('i08-owner-debt')::uuid,'future lease rejected');
RESET ROLE;
UPDATE public.rental_contracts SET start_date=(now() AT TIME ZONE 'America/Bogota')::date-30,
    status='TERMINATED' WHERE id=md5('i08-contract-debt')::uuid;
SET LOCAL ROLE fixup_app;
SELECT pg_temp.expect_denied(md5('i08-property-debt')::uuid,md5('i08-owner-debt')::uuid,'terminated lease rejected');
RESET ROLE;
UPDATE public.rental_contracts SET status='RENEWED',end_date=(now() AT TIME ZONE 'America/Bogota')::date
    WHERE id=md5('i08-contract-debt')::uuid;
SET LOCAL ROLE fixup_app;
SELECT pg_temp.assert_check(public.app_property_owner_has_outstanding_debt(md5('i08-property-debt')::uuid,md5('i08-owner-debt')::uuid),'renewed lease on inclusive last day allowed');
RESET ROLE;
UPDATE public.rental_contracts SET start_date=(now() AT TIME ZONE 'America/Bogota')::date,
    end_date=(now() AT TIME ZONE 'America/Bogota')::date+30 WHERE id=md5('i08-contract-debt')::uuid;
SET LOCAL ROLE fixup_app;
SELECT pg_temp.assert_check(public.app_property_owner_has_outstanding_debt(md5('i08-property-debt')::uuid,md5('i08-owner-debt')::uuid),'lease on first day allowed');
RESET ROLE;
UPDATE public.users SET status='SUSPENDED' WHERE id=md5('i08-tenant-debt')::uuid;
SET LOCAL ROLE fixup_app;
SELECT pg_temp.expect_denied(md5('i08-property-debt')::uuid,md5('i08-owner-debt')::uuid,'suspended tenant rejected');
RESET ROLE;
UPDATE public.users SET status='ACTIVE' WHERE id=md5('i08-tenant-debt')::uuid;
DELETE FROM public.user_roles WHERE user_id=md5('i08-manager-debt')::uuid;
SET LOCAL ROLE fixup_app;
SELECT set_config('app.current_user_id',md5('i08-manager-debt')::uuid::text,true),set_config('app.current_user_roles','REAL_ESTATE_MANAGER',true);
SELECT pg_temp.expect_denied(md5('i08-property-debt')::uuid,md5('i08-owner-debt')::uuid,'assigned manager without persisted role rejected');
RESET ROLE;
INSERT INTO public.user_roles VALUES(md5('i08-manager-debt')::uuid,'REAL_ESTATE_MANAGER');
SET LOCAL ROLE fixup_app;
SELECT set_config('app.current_user_id',md5('i08-tenant-debt')::uuid::text,true),set_config('app.current_user_roles','TENANT',true);
CREATE TEMP TABLE owner_debts(owner_user_id uuid,status text);
CREATE TEMP TABLE properties(id uuid,owner_user_id uuid,manager_user_id uuid);
SET LOCAL search_path=pg_temp,public;
SELECT pg_temp.assert_check(public.app_property_owner_has_outstanding_debt(md5('i08-property-debt')::uuid,md5('i08-owner-debt')::uuid),'temporary tables and caller search_path cannot hide debt');
RESET ROLE;
UPDATE public.owner_debts SET status='PAID',settled_at=now() WHERE id=md5('i08-debt')::uuid;
SET LOCAL ROLE fixup_app;
SELECT pg_temp.assert_check(NOT public.app_property_owner_has_outstanding_debt(md5('i08-property-debt')::uuid,md5('i08-owner-debt')::uuid),'paid debt no longer blocks authorized tenant');
SELECT pg_temp.assert_check((SELECT count(*)=0 FROM public.owner_debts),'paid debt details still hidden from tenant');
ROLLBACK;
