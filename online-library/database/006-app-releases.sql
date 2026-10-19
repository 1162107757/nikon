-- Review/apply manually after 005. No APK, key, sample release or default publication is inserted.
-- APK objects MUST remain in the private nikon-presets bucket; never grant public object reads.
BEGIN;
CREATE TABLE IF NOT EXISTS public.nikon_app_releases (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  package_name text NOT NULL CHECK(package_name='com.nikonpreset.usbprobe'),
  version_name text NOT NULL CHECK(length(version_name) BETWEEN 1 AND 100),
  version_code integer NOT NULL UNIQUE CHECK(version_code>0),
  min_sdk integer NOT NULL CHECK(min_sdk BETWEEN 24 AND 100),
  byte_length integer NOT NULL CHECK(byte_length BETWEEN 1 AND 4194304),
  sha256 text NOT NULL UNIQUE CHECK(sha256 ~ '^[a-f0-9]{64}$'),
  signer_sha256 text NOT NULL CHECK(signer_sha256='04db0f9d3d76c9831a0778cfabc1a0aaab984e05398da16eb644950ccfa0ae31'),
  storage_path text NOT NULL CHECK(storage_path='app-releases/'||sha256||'.apk'),
  channel text NOT NULL CHECK(channel IN ('stable','beta')),
  notes text NOT NULL CHECK(length(notes) BETWEEN 1 AND 6000),
  status text NOT NULL DEFAULT 'draft' CHECK(status IN ('draft','published','withdrawn')),
  revision integer NOT NULL DEFAULT 1 CHECK(revision>0),
  created_at timestamptz NOT NULL DEFAULT now(), published_at timestamptz, withdrawn_at timestamptz
);
CREATE INDEX IF NOT EXISTS nikon_app_release_channel ON public.nikon_app_releases(channel,version_code DESC) WHERE status IN ('published','withdrawn');
CREATE TABLE IF NOT EXISTS public.nikon_app_release_audit (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, release_id uuid NOT NULL REFERENCES public.nikon_app_releases(id),
  actor text NOT NULL, action text NOT NULL, details jsonb NOT NULL DEFAULT '{}', created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS public.nikon_app_uploads (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(), actor text NOT NULL,
  byte_length integer NOT NULL CHECK(byte_length BETWEEN 1 AND 4194304), channel text NOT NULL CHECK(channel IN ('stable','beta')),
  notes text NOT NULL CHECK(length(notes) BETWEEN 1 AND 6000),
  status text NOT NULL DEFAULT 'uploading' CHECK(status IN ('uploading','processing','complete')),
  created_at timestamptz NOT NULL DEFAULT now(), expires_at timestamptz NOT NULL DEFAULT now()+interval '1 hour'
);
CREATE TABLE IF NOT EXISTS public.nikon_app_upload_chunks (
  upload_id uuid NOT NULL REFERENCES public.nikon_app_uploads(id), chunk_index integer NOT NULL CHECK(chunk_index BETWEEN 0 AND 7),
  byte_length integer NOT NULL CHECK(byte_length BETWEEN 1 AND 524288), sha256 text NOT NULL CHECK(sha256 ~ '^[a-f0-9]{64}$'),
  storage_path text NOT NULL, PRIMARY KEY(upload_id,chunk_index),
  CHECK(storage_path='app-upload/'||upload_id::text||'/'||chunk_index::text||'-'||sha256||'.part')
);
CREATE TABLE IF NOT EXISTS public.nikon_app_release_budgets (
  budget_key text PRIMARY KEY, requests bigint NOT NULL DEFAULT 0, bytes bigint NOT NULL DEFAULT 0, expires_at timestamptz NOT NULL
);
-- Upgrade the previously deployed rootmain-only policy without rewriting audit history.
ALTER TABLE public.nikon_app_release_audit DROP CONSTRAINT IF EXISTS nikon_app_release_audit_actor_check;
ALTER TABLE public.nikon_app_release_audit ADD CONSTRAINT nikon_app_release_audit_actor_check CHECK(length(actor) BETWEEN 1 AND 64);
ALTER TABLE public.nikon_app_uploads DROP CONSTRAINT IF EXISTS nikon_app_uploads_actor_check;
ALTER TABLE public.nikon_app_uploads ADD CONSTRAINT nikon_app_uploads_actor_check CHECK(length(actor) BETWEEN 1 AND 64);
-- Existing uploaded rows are complete; new reservations explicitly start unready.
ALTER TABLE public.nikon_app_upload_chunks ADD COLUMN IF NOT EXISTS ready boolean NOT NULL DEFAULT true;
ALTER TABLE public.nikon_app_releases ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nikon_app_release_audit ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nikon_app_uploads ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nikon_app_upload_chunks ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nikon_app_release_budgets ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.nikon_app_releases,public.nikon_app_release_audit,public.nikon_app_uploads,public.nikon_app_upload_chunks,public.nikon_app_release_budgets FROM PUBLIC,anon,authenticated;
GRANT SELECT,INSERT,UPDATE ON public.nikon_app_releases,public.nikon_app_uploads,public.nikon_app_upload_chunks TO service_role;
GRANT SELECT,INSERT ON public.nikon_app_release_audit TO service_role;
GRANT SELECT,INSERT,UPDATE,DELETE ON public.nikon_app_release_budgets TO service_role;
GRANT USAGE,SELECT ON SEQUENCE public.nikon_app_release_audit_id_seq TO service_role;

CREATE OR REPLACE FUNCTION public.nikon_app_admin_allowed(p_actor text)
RETURNS boolean LANGUAGE sql STABLE SECURITY INVOKER SET search_path=public,pg_temp AS $$
 SELECT EXISTS(SELECT 1 FROM nikon_admins WHERE username=p_actor AND disabled=false);
$$;

-- Charge requests separately from bytes: every mutation counts, even an empty or
-- conflicting request, while both chunk and final-APK writes reserve actual bytes.
CREATE OR REPLACE FUNCTION public.nikon_app_upload_budget(p_actor text,p_bytes integer DEFAULT 0,p_count_request boolean DEFAULT true)
RETURNS jsonb LANGUAGE plpgsql SECURITY INVOKER SET search_path=public,pg_temp AS $$
DECLARE v_now timestamptz:=clock_timestamp(); v_request_key text; v_day_key text; v_requests bigint; v_bytes bigint;
BEGIN
 IF NOT nikon_app_admin_allowed(p_actor) THEN RETURN jsonb_build_object('error','FORBIDDEN'); END IF;
 IF p_bytes IS NULL OR p_bytes NOT BETWEEN 0 AND 4194304 OR p_count_request IS NULL THEN RETURN jsonb_build_object('error','INVALID'); END IF;
 PERFORM pg_advisory_xact_lock(hashtextextended('nikon-app-upload-actual-budget',0));
 v_request_key:='upload:requests:'||floor(extract(epoch FROM v_now)/900)::bigint::text;
 v_day_key:='upload:bytes:'||to_char(v_now AT TIME ZONE 'UTC','YYYYMMDD');
 SELECT requests INTO v_requests FROM nikon_app_release_budgets WHERE budget_key=v_request_key;
 SELECT bytes INTO v_bytes FROM nikon_app_release_budgets WHERE budget_key=v_day_key;
 IF (p_count_request AND coalesce(v_requests,0)>=256) OR coalesce(v_bytes,0)+p_bytes>67108864 THEN RETURN jsonb_build_object('error','BUDGET'); END IF;
 IF p_count_request THEN
  INSERT INTO nikon_app_release_budgets(budget_key,requests,bytes,expires_at) VALUES(v_request_key,1,0,v_now+interval '30 minutes')
  ON CONFLICT(budget_key) DO UPDATE SET requests=nikon_app_release_budgets.requests+1;
 END IF;
 IF p_bytes>0 THEN
  INSERT INTO nikon_app_release_budgets(budget_key,requests,bytes,expires_at) VALUES(v_day_key,0,p_bytes,v_now+interval '2 days')
  ON CONFLICT(budget_key) DO UPDATE SET bytes=nikon_app_release_budgets.bytes+p_bytes;
 END IF;
 DELETE FROM nikon_app_release_budgets WHERE expires_at<v_now;
 RETURN jsonb_build_object('allowed',true);
END; $$;

CREATE OR REPLACE FUNCTION public.nikon_app_release_immutable() RETURNS trigger LANGUAGE plpgsql SET search_path=public,pg_temp AS $$
BEGIN
  IF (NEW.id,NEW.package_name,NEW.version_name,NEW.version_code,NEW.min_sdk,NEW.byte_length,NEW.sha256,NEW.signer_sha256,NEW.storage_path,NEW.channel,NEW.created_at)
     IS DISTINCT FROM (OLD.id,OLD.package_name,OLD.version_name,OLD.version_code,OLD.min_sdk,OLD.byte_length,OLD.sha256,OLD.signer_sha256,OLD.storage_path,OLD.channel,OLD.created_at)
    OR (OLD.status<>'draft' AND NEW.notes IS DISTINCT FROM OLD.notes)
    OR (OLD.status='withdrawn' AND NEW.status<>'withdrawn')
    OR (OLD.status='published' AND NEW.status NOT IN ('published','withdrawn')) THEN RAISE EXCEPTION 'APP_RELEASE_IMMUTABLE'; END IF;
  RETURN NEW;
END; $$;
DROP TRIGGER IF EXISTS nikon_app_release_immutable ON public.nikon_app_releases;
CREATE TRIGGER nikon_app_release_immutable BEFORE UPDATE ON public.nikon_app_releases FOR EACH ROW EXECUTE FUNCTION public.nikon_app_release_immutable();

CREATE OR REPLACE FUNCTION public.nikon_release_budget_atomic(p_key text,p_kind text,p_bytes integer DEFAULT 0)
RETURNS jsonb LANGUAGE plpgsql SECURITY INVOKER SET search_path=public,pg_temp AS $$
DECLARE v_now timestamptz:=clock_timestamp(); v_keys text[]; v_caps bigint[]; v_bytecaps bigint[]; v_expiry timestamptz[]; v_row nikon_app_release_budgets; i integer;
BEGIN
 IF p_key !~ '^[a-f0-9]{64}$' OR p_kind NOT IN ('metadata','download') OR p_bytes<0 OR p_bytes>4194304 THEN RETURN jsonb_build_object('error','INVALID'); END IF;
 PERFORM pg_advisory_xact_lock(hashtextextended('nikon-app-release-budget',0));
 IF p_kind='metadata' THEN
  v_keys:=ARRAY['meta:ip:'||p_key||':'||to_char(v_now,'YYYYMMDDHH24MI'),'meta:global:'||to_char(v_now,'YYYYMMDDHH24MI')];
  v_caps:=ARRAY[120,1200]; v_bytecaps:=ARRAY[0,0]; v_expiry:=ARRAY[v_now+interval '2 minutes',v_now+interval '2 minutes'];
 ELSE
  v_keys:=ARRAY['file:ip:'||p_key||':'||to_char(v_now,'YYYYMMDDHH24'),'file:global:'||to_char(v_now,'YYYYMMDD')];
  v_caps:=ARRAY[20,1000]; v_bytecaps:=ARRAY[83886080,2147483648]; v_expiry:=ARRAY[v_now+interval '2 hours',v_now+interval '2 days'];
 END IF;
 FOR i IN 1..2 LOOP
  SELECT * INTO v_row FROM nikon_app_release_budgets WHERE budget_key=v_keys[i];
  IF FOUND AND (v_row.requests>=v_caps[i] OR (v_bytecaps[i]>0 AND v_row.bytes+p_bytes>v_bytecaps[i])) THEN RETURN jsonb_build_object('error','BUDGET'); END IF;
 END LOOP;
 FOR i IN 1..2 LOOP
  INSERT INTO nikon_app_release_budgets(budget_key,requests,bytes,expires_at) VALUES(v_keys[i],1,p_bytes,v_expiry[i])
  ON CONFLICT(budget_key) DO UPDATE SET requests=nikon_app_release_budgets.requests+1,bytes=nikon_app_release_budgets.bytes+p_bytes;
 END LOOP;
 DELETE FROM nikon_app_release_budgets WHERE expires_at<v_now;
 RETURN jsonb_build_object('allowed',true);
END; $$;

CREATE OR REPLACE FUNCTION public.nikon_app_upload_begin(p_actor text,p_size integer,p_channel text,p_notes text)
RETURNS jsonb LANGUAGE plpgsql SECURITY INVOKER SET search_path=public,pg_temp AS $$
DECLARE v_row nikon_app_uploads;
BEGIN
 IF NOT nikon_app_admin_allowed(p_actor) THEN RETURN jsonb_build_object('error','FORBIDDEN'); END IF;
 IF p_size NOT BETWEEN 1 AND 4194304 OR p_channel NOT IN ('stable','beta') OR p_notes IS NULL OR length(p_notes) NOT BETWEEN 1 AND 6000 THEN RETURN jsonb_build_object('error','INVALID'); END IF;
 PERFORM pg_advisory_xact_lock(hashtextextended('nikon-app-upload',0));
 IF (SELECT count(*) FROM nikon_app_uploads WHERE status='uploading' AND expires_at>now())>=4
 OR (SELECT coalesce(sum(byte_length),0) FROM nikon_app_uploads WHERE created_at>=date_trunc('day',now()))+p_size>67108864 THEN RETURN jsonb_build_object('error','BUDGET'); END IF;
 INSERT INTO nikon_app_uploads(actor,byte_length,channel,notes) VALUES(p_actor,p_size,p_channel,p_notes) RETURNING * INTO v_row;
 RETURN jsonb_build_object('uploadId',v_row.id,'expiresAt',v_row.expires_at);
END; $$;

CREATE OR REPLACE FUNCTION public.nikon_app_upload_check(p_actor text,p_id uuid,p_index integer,p_size integer)
RETURNS jsonb LANGUAGE plpgsql SECURITY INVOKER SET search_path=public,pg_temp AS $$
DECLARE v_row nikon_app_uploads;
BEGIN
 IF NOT nikon_app_admin_allowed(p_actor) THEN RETURN jsonb_build_object('error','FORBIDDEN'); END IF;
 SELECT * INTO v_row FROM nikon_app_uploads WHERE id=p_id AND actor=p_actor FOR UPDATE;
 IF NOT FOUND OR v_row.status<>'uploading' OR v_row.expires_at<=now() THEN RETURN jsonb_build_object('error','UPLOAD_EXPIRED'); END IF;
 IF p_index<0 OR p_index>7 OR p_index*524288>=v_row.byte_length OR p_size<>least(524288,v_row.byte_length-p_index*524288) THEN RETURN jsonb_build_object('error','INVALID'); END IF;
 RETURN jsonb_build_object('allowed',true);
END; $$;

CREATE OR REPLACE FUNCTION public.nikon_app_upload_reserve(p_actor text,p_id uuid,p_index integer,p_size integer,p_sha256 text,p_path text)
RETURNS jsonb LANGUAGE plpgsql SECURITY INVOKER SET search_path=public,pg_temp AS $$
DECLARE v_check jsonb; v_chunk nikon_app_upload_chunks;
BEGIN
 -- The upload row lock acquired here also serializes concurrent different hashes.
 v_check:=nikon_app_upload_check(p_actor,p_id,p_index,p_size); IF v_check?'error' THEN RETURN v_check; END IF;
 IF p_sha256 IS NULL OR p_sha256 !~ '^[a-f0-9]{64}$' OR p_path IS DISTINCT FROM 'app-upload/'||p_id::text||'/'||p_index::text||'-'||p_sha256||'.part' THEN RETURN jsonb_build_object('error','INVALID'); END IF;
 SELECT * INTO v_chunk FROM nikon_app_upload_chunks WHERE upload_id=p_id AND chunk_index=p_index;
 IF FOUND THEN
  IF v_chunk.sha256<>p_sha256 OR v_chunk.byte_length<>p_size THEN RETURN jsonb_build_object('error','CONFLICT'); END IF;
 ELSE INSERT INTO nikon_app_upload_chunks(upload_id,chunk_index,byte_length,sha256,storage_path,ready) VALUES(p_id,p_index,p_size,p_sha256,p_path,false); END IF;
 RETURN jsonb_build_object('reserved',true);
END; $$;

CREATE OR REPLACE FUNCTION public.nikon_app_upload_chunk(p_actor text,p_id uuid,p_index integer,p_size integer,p_sha256 text,p_path text)
RETURNS jsonb LANGUAGE plpgsql SECURITY INVOKER SET search_path=public,pg_temp AS $$
DECLARE v_check jsonb; v_chunk nikon_app_upload_chunks;
BEGIN
 v_check:=nikon_app_upload_check(p_actor,p_id,p_index,p_size); IF v_check?'error' THEN RETURN v_check; END IF;
 IF p_sha256 !~ '^[a-f0-9]{64}$' OR p_path<>'app-upload/'||p_id::text||'/'||p_index::text||'-'||p_sha256||'.part' THEN RETURN jsonb_build_object('error','INVALID'); END IF;
 SELECT * INTO v_chunk FROM nikon_app_upload_chunks WHERE upload_id=p_id AND chunk_index=p_index;
 IF FOUND THEN
  IF v_chunk.sha256<>p_sha256 OR v_chunk.byte_length<>p_size THEN RETURN jsonb_build_object('error','CONFLICT'); END IF;
  UPDATE nikon_app_upload_chunks SET ready=true WHERE upload_id=p_id AND chunk_index=p_index;
 ELSE INSERT INTO nikon_app_upload_chunks(upload_id,chunk_index,byte_length,sha256,storage_path) VALUES(p_id,p_index,p_size,p_sha256,p_path); END IF;
 RETURN jsonb_build_object('uploaded',true);
END; $$;

CREATE OR REPLACE FUNCTION public.nikon_app_upload_claim(p_actor text,p_id uuid)
RETURNS jsonb LANGUAGE plpgsql SECURITY INVOKER SET search_path=public,pg_temp AS $$
DECLARE v_row nikon_app_uploads; v_chunks jsonb; v_count integer; v_size bigint;
BEGIN
 IF NOT nikon_app_admin_allowed(p_actor) THEN RETURN jsonb_build_object('error','FORBIDDEN'); END IF;
 SELECT * INTO v_row FROM nikon_app_uploads WHERE id=p_id AND actor=p_actor FOR UPDATE;
 IF NOT FOUND OR v_row.status<>'uploading' OR v_row.expires_at<=now() THEN RETURN jsonb_build_object('error','UPLOAD_EXPIRED'); END IF;
 SELECT count(*),coalesce(sum(byte_length),0),jsonb_agg(jsonb_build_object('index',chunk_index,'size',byte_length,'sha256',sha256,'path',storage_path) ORDER BY chunk_index)
 INTO v_count,v_size,v_chunks FROM nikon_app_upload_chunks WHERE upload_id=p_id AND ready=true;
 IF v_count<>(v_row.byte_length+524287)/524288 OR v_size<>v_row.byte_length THEN RETURN jsonb_build_object('error','INCOMPLETE'); END IF;
 UPDATE nikon_app_uploads SET status='processing' WHERE id=p_id;
 RETURN jsonb_build_object('byteLength',v_row.byte_length,'chunks',v_chunks);
END; $$;

CREATE OR REPLACE FUNCTION public.nikon_app_release_create(p_actor text,p_upload_id uuid,p_metadata jsonb,p_path text)
RETURNS jsonb LANGUAGE plpgsql SECURITY INVOKER SET search_path=public,pg_temp AS $$
DECLARE v_upload nikon_app_uploads; v_row nikon_app_releases;
BEGIN
 IF NOT nikon_app_admin_allowed(p_actor) THEN RETURN jsonb_build_object('error','FORBIDDEN'); END IF;
 SELECT * INTO v_upload FROM nikon_app_uploads WHERE id=p_upload_id AND actor=p_actor FOR UPDATE;
 IF NOT FOUND OR v_upload.status<>'processing' OR v_upload.expires_at<=now() THEN RETURN jsonb_build_object('error','UPLOAD_EXPIRED'); END IF;
 IF (p_metadata->>'byteLength')::integer<>v_upload.byte_length THEN RETURN jsonb_build_object('error','INVALID'); END IF;
 INSERT INTO nikon_app_releases(package_name,version_name,version_code,min_sdk,byte_length,sha256,signer_sha256,storage_path,channel,notes)
 VALUES(p_metadata->>'packageName',p_metadata->>'versionName',(p_metadata->>'versionCode')::integer,(p_metadata->>'minSdk')::integer,
 (p_metadata->>'byteLength')::integer,p_metadata->>'sha256',p_metadata->>'signerSha256',p_path,v_upload.channel,v_upload.notes) RETURNING * INTO v_row;
 UPDATE nikon_app_uploads SET status='complete' WHERE id=p_upload_id;
 INSERT INTO nikon_app_release_audit(release_id,actor,action,details) VALUES(v_row.id,p_actor,'upload_verified',jsonb_build_object('sha256',v_row.sha256,'versionCode',v_row.version_code,'signatureSchemes',p_metadata->'signatureSchemes'));
 RETURN to_jsonb(v_row);
EXCEPTION WHEN unique_violation THEN RETURN jsonb_build_object('error','DUPLICATE');
 WHEN check_violation OR invalid_text_representation OR not_null_violation THEN RETURN jsonb_build_object('error','INVALID');
END; $$;

CREATE OR REPLACE FUNCTION public.nikon_app_release_action(p_actor text,p_id uuid,p_revision integer,p_action text,p_notes text DEFAULT '',p_confirmation text DEFAULT '')
RETURNS jsonb LANGUAGE plpgsql SECURITY INVOKER SET search_path=public,pg_temp AS $$
DECLARE v_row nikon_app_releases; v_confirmation text;
BEGIN
 IF NOT nikon_app_admin_allowed(p_actor) THEN RETURN jsonb_build_object('error','FORBIDDEN'); END IF;
 PERFORM pg_advisory_xact_lock(hashtextextended('nikon-app-release-publish',0));
 SELECT * INTO v_row FROM nikon_app_releases WHERE id=p_id FOR UPDATE;
 IF NOT FOUND THEN RETURN jsonb_build_object('error','NOT_FOUND'); END IF;
 IF p_revision IS DISTINCT FROM v_row.revision THEN RETURN jsonb_build_object('error','CONFLICT'); END IF;
 IF p_action='edit' THEN
  IF v_row.status<>'draft' OR p_notes IS NULL OR length(p_notes) NOT BETWEEN 1 AND 6000 THEN RETURN jsonb_build_object('error','INVALID'); END IF;
  UPDATE nikon_app_releases SET notes=p_notes,revision=revision+1 WHERE id=p_id RETURNING * INTO v_row;
 ELSIF p_action='publish' THEN
  IF v_row.status<>'draft' THEN RETURN jsonb_build_object('error','CONFLICT'); END IF;
  v_confirmation:='PUBLISH '||v_row.version_code::text||' '||v_row.sha256;
  IF p_confirmation IS DISTINCT FROM v_confirmation THEN RETURN jsonb_build_object('error','CONFIRMATION'); END IF;
  IF EXISTS(SELECT 1 FROM nikon_app_releases WHERE channel=v_row.channel AND status IN ('published','withdrawn') AND version_code>=v_row.version_code) THEN RETURN jsonb_build_object('error','DOWNGRADE'); END IF;
  UPDATE nikon_app_releases SET status='published',published_at=now(),revision=revision+1 WHERE id=p_id RETURNING * INTO v_row;
 ELSIF p_action='withdraw' THEN
  IF v_row.status<>'published' THEN RETURN jsonb_build_object('error','CONFLICT'); END IF;
  IF p_confirmation IS DISTINCT FROM 'WITHDRAW '||v_row.version_code::text THEN RETURN jsonb_build_object('error','CONFIRMATION'); END IF;
  UPDATE nikon_app_releases SET status='withdrawn',withdrawn_at=now(),revision=revision+1 WHERE id=p_id RETURNING * INTO v_row;
 ELSE RETURN jsonb_build_object('error','INVALID'); END IF;
 INSERT INTO nikon_app_release_audit(release_id,actor,action,details) VALUES(p_id,p_actor,p_action,jsonb_build_object('versionCode',v_row.version_code,'sha256',v_row.sha256,'channel',v_row.channel,'revision',v_row.revision));
 RETURN to_jsonb(v_row);
END; $$;

REVOKE ALL ON FUNCTION public.nikon_app_admin_allowed(text),public.nikon_app_upload_budget(text,integer,boolean),public.nikon_app_upload_reserve(text,uuid,integer,integer,text,text),public.nikon_app_release_immutable(),public.nikon_release_budget_atomic(text,text,integer),public.nikon_app_upload_begin(text,integer,text,text),public.nikon_app_upload_check(text,uuid,integer,integer),public.nikon_app_upload_chunk(text,uuid,integer,integer,text,text),public.nikon_app_upload_claim(text,uuid),public.nikon_app_release_create(text,uuid,jsonb,text),public.nikon_app_release_action(text,uuid,integer,text,text,text) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.nikon_app_admin_allowed(text),public.nikon_app_upload_budget(text,integer,boolean),public.nikon_app_upload_reserve(text,uuid,integer,integer,text,text),public.nikon_release_budget_atomic(text,text,integer),public.nikon_app_upload_begin(text,integer,text,text),public.nikon_app_upload_check(text,uuid,integer,integer),public.nikon_app_upload_chunk(text,uuid,integer,integer,text,text),public.nikon_app_upload_claim(text,uuid),public.nikon_app_release_create(text,uuid,jsonb,text),public.nikon_app_release_action(text,uuid,integer,text,text,text) TO service_role;
NOTIFY pgrst,'reload schema';
COMMIT;

-- Rollback is a separate manual review action. Preserve/download audit evidence and private objects first.
-- BEGIN;
-- DROP FUNCTION public.nikon_app_release_action(text,uuid,integer,text,text,text),public.nikon_app_release_create(text,uuid,jsonb,text),public.nikon_app_upload_claim(text,uuid),public.nikon_app_upload_chunk(text,uuid,integer,integer,text,text),public.nikon_app_upload_reserve(text,uuid,integer,integer,text,text),public.nikon_app_upload_check(text,uuid,integer,integer),public.nikon_app_upload_begin(text,integer,text,text),public.nikon_app_upload_budget(text,integer,boolean),public.nikon_app_admin_allowed(text),public.nikon_release_budget_atomic(text,text,integer);
-- DROP TABLE public.nikon_app_release_audit,public.nikon_app_upload_chunks,public.nikon_app_uploads,public.nikon_app_release_budgets,public.nikon_app_releases;
-- DROP FUNCTION public.nikon_app_release_immutable();
-- NOTIFY pgrst,'reload schema';
-- COMMIT;
