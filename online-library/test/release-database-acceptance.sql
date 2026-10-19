-- Local PostgreSQL acceptance only. All fixture rows and publications roll back.
-- Run after 001..006 against an isolated test database, never a production endpoint.
BEGIN;
INSERT INTO public.nikon_admins(username,password_hash,disabled) VALUES
 ('admin','synthetic-unused-hash',false),('rootmain','synthetic-unused-hash',false),('disabled-release-admin','synthetic-unused-hash',true)
ON CONFLICT(username) DO UPDATE SET disabled=excluded.disabled;
SET LOCAL ROLE service_role;
DO $$
DECLARE u jsonb; r jsonb; c jsonb; v_release_id uuid; upload_id uuid; metadata jsonb;
BEGIN
  IF has_function_privilege('anon','public.nikon_app_upload_budget(text,integer,boolean)','EXECUTE')
   OR has_function_privilege('authenticated','public.nikon_app_upload_reserve(text,uuid,integer,integer,text,text)','EXECUTE')
   OR has_table_privilege('anon','public.nikon_app_upload_chunks','SELECT') THEN RAISE EXCEPTION 'upload internals are accessible without service_role'; END IF;
  u:=nikon_app_upload_begin('other',4,'beta','Synthetic rollback fixture');
  IF u->>'error'<>'FORBIDDEN' THEN RAISE EXCEPTION 'non-administrator upload permitted'; END IF;
  u:=nikon_app_upload_begin('disabled-release-admin',4,'beta','Synthetic rollback fixture');
  IF u->>'error'<>'FORBIDDEN' THEN RAISE EXCEPTION 'disabled administrator upload permitted'; END IF;
  IF nikon_app_upload_budget('other',0,true)->>'error'<>'FORBIDDEN'
   OR nikon_app_upload_check('other',null,0,4)->>'error'<>'FORBIDDEN'
   OR nikon_app_upload_reserve('other',null,0,4,repeat('a',64),'unused')->>'error'<>'FORBIDDEN'
   OR nikon_app_upload_chunk('other',null,0,4,repeat('a',64),'unused')->>'error'<>'FORBIDDEN'
   OR nikon_app_upload_claim('other',null)->>'error'<>'FORBIDDEN'
   OR nikon_app_release_create('other',null,'{}','unused')->>'error'<>'FORBIDDEN'
   OR nikon_app_release_action('other',null,1,'publish','','')->>'error'<>'FORBIDDEN' THEN RAISE EXCEPTION 'an RPC trusts a nonexistent administrator'; END IF;
  u:=nikon_app_upload_begin('admin',4,'beta','Synthetic rollback fixture'); upload_id:=(u->>'uploadId')::uuid;
  c:=nikon_app_upload_claim('rootmain',upload_id); IF c->>'error'<>'UPLOAD_EXPIRED' THEN RAISE EXCEPTION 'another administrator claimed a private upload task'; END IF;
  c:=nikon_app_upload_claim('admin',upload_id); IF c->>'error'<>'INCOMPLETE' THEN RAISE EXCEPTION 'incomplete upload claimed'; END IF;
  c:=nikon_app_upload_reserve('admin',upload_id,0,4,repeat('a',64),'app-upload/'||upload_id::text||'/0-'||repeat('a',64)||'.part');
  IF c?'error' THEN RAISE EXCEPTION 'reservation failed %',c; END IF;
  c:=nikon_app_upload_claim('admin',upload_id); IF c->>'error'<>'INCOMPLETE' THEN RAISE EXCEPTION 'reserved chunk treated as stored'; END IF;
  c:=nikon_app_upload_reserve('admin',upload_id,0,4,repeat('b',64),'app-upload/'||upload_id::text||'/0-'||repeat('b',64)||'.part');
  IF c->>'error'<>'CONFLICT' THEN RAISE EXCEPTION 'conflict was not rejected before storage'; END IF;
  c:=nikon_app_upload_chunk('admin',upload_id,0,4,repeat('a',64),'app-upload/'||upload_id::text||'/0-'||repeat('a',64)||'.part');
  IF c?'error' THEN RAISE EXCEPTION 'chunk failed %',c; END IF;
  c:=nikon_app_upload_chunk('admin',upload_id,0,4,repeat('b',64),'app-upload/'||upload_id::text||'/0-'||repeat('b',64)||'.part');
  IF c->>'error'<>'CONFLICT' THEN RAISE EXCEPTION 'chunk mutated'; END IF;
  c:=nikon_app_upload_claim('admin',upload_id); IF c?'error' THEN RAISE EXCEPTION 'claim failed %',c; END IF;
  metadata:=jsonb_build_object('packageName','com.nikonpreset.usbprobe','versionName','synthetic-rollback-only','versionCode',2147000000,'minSdk',26,'byteLength',4,'sha256',repeat('a',64),'signerSha256','04db0f9d3d76c9831a0778cfabc1a0aaab984e05398da16eb644950ccfa0ae31','signatureSchemes',jsonb_build_array(2,3));
  r:=nikon_app_release_create('admin',upload_id,metadata,'app-releases/'||repeat('a',64)||'.apk');
  IF r?'error' THEN RAISE EXCEPTION 'create failed %',r; END IF; v_release_id:=(r->>'id')::uuid;
  c:=nikon_app_release_action('rootmain',v_release_id,1,'publish','','yes'); IF c->>'error'<>'CONFIRMATION' THEN RAISE EXCEPTION 'publish lacked confirmation'; END IF;
  c:=nikon_app_release_action('rootmain',v_release_id,1,'publish','','PUBLISH 2147000000 '||repeat('a',64)); IF c->>'status'<>'published' THEN RAISE EXCEPTION 'publish failed %',c; END IF;
  c:=nikon_app_release_action('rootmain',v_release_id,1,'withdraw','','WITHDRAW 2147000000'); IF c->>'error'<>'CONFLICT' THEN RAISE EXCEPTION 'stale action allowed'; END IF;
  c:=nikon_app_release_action('rootmain',v_release_id,2,'edit','changed',''); IF c->>'error'<>'INVALID' THEN RAISE EXCEPTION 'published notes mutable'; END IF;
  BEGIN UPDATE nikon_app_releases SET version_name='mutated' WHERE id=v_release_id; RAISE EXCEPTION 'immutable metadata mutated'; EXCEPTION WHEN raise_exception THEN IF SQLERRM<>'APP_RELEASE_IMMUTABLE' THEN RAISE; END IF; END;
  c:=nikon_app_release_action('admin',v_release_id,2,'withdraw','','WITHDRAW 2147000000'); IF c->>'status'<>'withdrawn' THEN RAISE EXCEPTION 'withdraw failed'; END IF;
  c:=nikon_app_release_action('rootmain',v_release_id,3,'publish','','PUBLISH 2147000000 '||repeat('a',64)); IF c->>'error'<>'CONFLICT' THEN RAISE EXCEPTION 'withdrawn version republished'; END IF;
  IF (SELECT count(*) FROM nikon_app_release_audit WHERE release_id=v_release_id AND actor='admin')<>2
   OR (SELECT count(*) FROM nikon_app_release_audit WHERE release_id=v_release_id AND actor='rootmain')<>1 THEN RAISE EXCEPTION 'audit does not identify the real administrator'; END IF;
  u:=nikon_app_upload_begin('admin',4,'beta','duplicate rollback fixture'); upload_id:=(u->>'uploadId')::uuid;
  PERFORM nikon_app_upload_chunk('admin',upload_id,0,4,repeat('a',64),'app-upload/'||upload_id::text||'/0-'||repeat('a',64)||'.part');PERFORM nikon_app_upload_claim('admin',upload_id);
  c:=nikon_app_release_create('admin',upload_id,metadata,'app-releases/'||repeat('a',64)||'.apk'); IF c->>'error'<>'DUPLICATE' THEN RAISE EXCEPTION 'duplicate accepted'; END IF;
  FOR i IN 1..20 LOOP c:=nikon_release_budget_atomic(repeat('c',64),'download',4194304); IF c?'error' THEN RAISE EXCEPTION 'premature budget failure'; END IF; END LOOP;
  c:=nikon_release_budget_atomic(repeat('c',64),'download',1); IF c->>'error'<>'BUDGET' THEN RAISE EXCEPTION 'download budget not atomic'; END IF;
  FOR i IN 1..256 LOOP c:=nikon_app_upload_budget('admin',0,true); IF c?'error' THEN RAISE EXCEPTION 'premature request budget failure'; END IF; END LOOP;
  c:=nikon_app_upload_budget('rootmain',0,true); IF c->>'error'<>'BUDGET' THEN RAISE EXCEPTION 'zero-byte/global request ceiling missing'; END IF;
  FOR i IN 1..16 LOOP c:=nikon_app_upload_budget('admin',4194304,false); IF c?'error' THEN RAISE EXCEPTION 'premature actual byte budget failure'; END IF; END LOOP;
  c:=nikon_app_upload_budget('rootmain',1,false); IF c->>'error'<>'BUDGET' THEN RAISE EXCEPTION 'actual upload byte ceiling missing'; END IF;
  IF (SELECT sum(bytes) FROM nikon_app_release_budgets WHERE budget_key LIKE 'upload:bytes:%')<>67108864 THEN RAISE EXCEPTION 'byte reservation was not exact'; END IF;
END; $$;
ROLLBACK;
