-- Additive V1 community schema. Apply after 001..004. No seed content or destructive migration.
CREATE TABLE IF NOT EXISTS public.nikon_community_settings (
  singleton boolean PRIMARY KEY DEFAULT true CHECK(singleton),
  settings jsonb NOT NULL DEFAULT '{"newDaySubmissions":1,"dailySubmissions":3,"pendingSubmissions":3,"newDayComments":10,"dailyComments":50,"commentIntervalSeconds":30,"newDayUploads":4,"dailyUploads":12}'::jsonb
);
INSERT INTO public.nikon_community_settings(singleton) VALUES(true) ON CONFLICT DO NOTHING;
CREATE TABLE IF NOT EXISTS public.nikon_community_restrictions (
 user_id uuid PRIMARY KEY REFERENCES public.nikon_users(id), muted_until timestamptz, submission_blocked_until timestamptz,
 reason text NOT NULL DEFAULT '', updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS public.nikon_community_assets (
 user_id uuid NOT NULL REFERENCES public.nikon_users(id), id text NOT NULL CHECK(id ~ '^(ncp|preview)-[a-f0-9]{64}$'),
 payload jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(user_id,id)
);
CREATE TABLE IF NOT EXISTS public.nikon_community_submissions (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), user_id uuid NOT NULL REFERENCES public.nikon_users(id),
 revision integer NOT NULL DEFAULT 1 CHECK(revision>0), status text NOT NULL DEFAULT 'draft' CHECK(status IN('draft','pending','published','rejected','withdrawn')),
 draft jsonb NOT NULL, published jsonb, published_revision integer, published_at timestamptz, reason text NOT NULL DEFAULT '',
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS nikon_community_submission_owner ON public.nikon_community_submissions(user_id,created_at DESC);
CREATE INDEX IF NOT EXISTS nikon_community_submission_feed ON public.nikon_community_submissions(published_at DESC,id) WHERE published IS NOT NULL;
CREATE INDEX IF NOT EXISTS nikon_community_submission_file ON public.nikon_community_submissions(user_id,((draft->'file'->>'sha256')));
CREATE TABLE IF NOT EXISTS public.nikon_community_versions (
 preset_id uuid NOT NULL REFERENCES public.nikon_community_submissions(id), revision integer NOT NULL,
 snapshot jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(preset_id,revision)
);
CREATE TABLE IF NOT EXISTS public.nikon_community_activity (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, user_id uuid NOT NULL REFERENCES public.nikon_users(id),
 kind text NOT NULL CHECK(kind IN('upload','submit','comment','report')), created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS nikon_community_activity_budget ON public.nikon_community_activity(user_id,kind,created_at DESC);
CREATE TABLE IF NOT EXISTS public.nikon_community_comments (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), preset_id uuid NOT NULL, user_id uuid NOT NULL REFERENCES public.nikon_users(id),
 parent_id uuid REFERENCES public.nikon_community_comments(id), text text NOT NULL CHECK(length(text) BETWEEN 1 AND 500),
 status text NOT NULL DEFAULT 'pending' CHECK(status IN('visible','deleted','hidden','pending')), reason text NOT NULL DEFAULT '',
 was_public boolean NOT NULL DEFAULT false,
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now()
);
-- 005 is already deployed. Reapplying safely upgrades the original three-state CHECK without rewriting existing comments.
ALTER TABLE public.nikon_community_comments DROP CONSTRAINT IF EXISTS nikon_community_comments_status_check;
ALTER TABLE public.nikon_community_comments ADD CONSTRAINT nikon_community_comments_status_check CHECK(status IN('visible','deleted','hidden','pending'));
ALTER TABLE public.nikon_community_comments ALTER COLUMN status SET DEFAULT 'pending';
-- Existing deleted comments retain their public tombstones; a cancelled pending comment must never become one.
ALTER TABLE public.nikon_community_comments ADD COLUMN IF NOT EXISTS was_public boolean NOT NULL DEFAULT true;
ALTER TABLE public.nikon_community_comments ALTER COLUMN was_public SET DEFAULT false;
CREATE INDEX IF NOT EXISTS nikon_community_comments_preset ON public.nikon_community_comments(preset_id,created_at,id);
CREATE TABLE IF NOT EXISTS public.nikon_community_reports (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), comment_id uuid NOT NULL REFERENCES public.nikon_community_comments(id),
 user_id uuid NOT NULL REFERENCES public.nikon_users(id), reason text NOT NULL CHECK(length(reason) BETWEEN 1 AND 500),
 status text NOT NULL DEFAULT 'open' CHECK(status IN('open','resolved','dismissed')), review_reason text NOT NULL DEFAULT '',
 created_at timestamptz NOT NULL DEFAULT now(), UNIQUE(comment_id,user_id)
);
CREATE TABLE IF NOT EXISTS public.nikon_community_messages (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), user_id uuid NOT NULL REFERENCES public.nikon_users(id),
 type text NOT NULL DEFAULT 'reply' CHECK(type IN('reply','review_approved','review_rejected','review_withdrawn')),
 comment_id uuid UNIQUE REFERENCES public.nikon_community_comments(id), parent_id uuid REFERENCES public.nikon_community_comments(id),
 submission_id uuid REFERENCES public.nikon_community_submissions(id), revision integer, review_reason text NOT NULL DEFAULT '',
 read_at timestamptz, created_at timestamptz NOT NULL DEFAULT now(), UNIQUE(submission_id,revision,type),
 CHECK((type='reply' AND comment_id IS NOT NULL AND parent_id IS NOT NULL) OR (type<>'reply' AND submission_id IS NOT NULL AND revision IS NOT NULL))
);
CREATE INDEX IF NOT EXISTS nikon_community_messages_owner ON public.nikon_community_messages(user_id,created_at DESC,id);
CREATE TABLE IF NOT EXISTS public.nikon_community_idempotency (
 user_id uuid NOT NULL REFERENCES public.nikon_users(id), action text NOT NULL, key text NOT NULL,
 request_hash text NOT NULL, result jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(user_id,action,key)
);
CREATE TABLE IF NOT EXISTS public.nikon_community_audit (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, actor text NOT NULL, action text NOT NULL,
 target text NOT NULL DEFAULT '', detail jsonb NOT NULL DEFAULT '{}'::jsonb, created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS public.nikon_abuse_windows (
 key text PRIMARY KEY, count integer NOT NULL DEFAULT 1, window_start timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS public.nikon_community_risk_windows (
 scope text NOT NULL, source_hash text NOT NULL, window_start timestamptz NOT NULL,
 blocked_count bigint NOT NULL DEFAULT 1, audit_id bigint REFERENCES public.nikon_community_audit(id),
 PRIMARY KEY(scope,source_hash,window_start)
);
CREATE TABLE IF NOT EXISTS public.nikon_auth_challenges (
 id uuid PRIMARY KEY, answer_hash text NOT NULL CHECK(answer_hash ~ '^[a-f0-9]{64}$'),
 source_hash text NOT NULL CHECK(source_hash ~ '^[a-f0-9]{64}$'), installation_hash text NOT NULL CHECK(installation_hash ~ '^[a-f0-9]{64}$'),
 expires_at timestamptz NOT NULL, consumed_at timestamptz, created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS nikon_auth_challenge_expiry ON public.nikon_auth_challenges(expires_at);

-- Callers cannot supply raw IPs, passwords or tokens to audit. Limits persist and commit even on rejection.
CREATE OR REPLACE FUNCTION public.nikon_abuse_tick(p_key text,p_max integer,p_seconds integer)
RETURNS boolean LANGUAGE plpgsql SECURITY INVOKER SET search_path=public,pg_temp AS $$
DECLARE n integer;
BEGIN
 INSERT INTO nikon_abuse_windows(key) VALUES(p_key) ON CONFLICT(key) DO UPDATE SET
 count=CASE WHEN nikon_abuse_windows.window_start < now()-make_interval(secs=>p_seconds) THEN 1 ELSE nikon_abuse_windows.count+1 END,
 window_start=CASE WHEN nikon_abuse_windows.window_start < now()-make_interval(secs=>p_seconds) THEN now() ELSE nikon_abuse_windows.window_start END RETURNING count INTO n;
 RETURN n<=p_max;
END; $$;
CREATE OR REPLACE FUNCTION public.nikon_community_risk(p_scope text,p_source_hash text)
RETURNS void LANGUAGE plpgsql SECURITY INVOKER SET search_path=public,pg_temp AS $$
DECLARE bucket timestamptz:=to_timestamp(floor(extract(epoch FROM now())/900)*900); n bigint; event_id bigint;
BEGIN
 INSERT INTO nikon_community_risk_windows(scope,source_hash,window_start) VALUES(p_scope,p_source_hash,bucket)
 ON CONFLICT(scope,source_hash,window_start) DO UPDATE SET blocked_count=nikon_community_risk_windows.blocked_count+1 RETURNING blocked_count,audit_id INTO n,event_id;
 IF n=1 THEN
  INSERT INTO nikon_community_audit(actor,action,target,detail) VALUES('system','abuse:'||p_scope,p_source_hash,jsonb_build_object('windowStart',bucket,'blockedCount',n)) RETURNING id INTO event_id;
  UPDATE nikon_community_risk_windows SET audit_id=event_id WHERE scope=p_scope AND source_hash=p_source_hash AND window_start=bucket;
 ELSE UPDATE nikon_community_audit SET detail=detail||jsonb_build_object('blockedCount',n) WHERE id=event_id;
 END IF;
END; $$;
CREATE OR REPLACE FUNCTION public.nikon_community_cleanup()
RETURNS void LANGUAGE plpgsql SECURITY INVOKER SET search_path=public,pg_temp AS $$
BEGIN
 -- At most one small batch per hour; retention is eventual and never scans/deletes an unbounded set.
 IF NOT nikon_abuse_tick('community:cleanup',1,3600) THEN RETURN; END IF;
 DELETE FROM nikon_community_risk_windows WHERE (scope,source_hash,window_start) IN (SELECT scope,source_hash,window_start FROM nikon_community_risk_windows WHERE window_start<now()-interval '2 days' ORDER BY window_start LIMIT 1000);
 DELETE FROM nikon_community_audit WHERE id IN (SELECT id FROM nikon_community_audit WHERE created_at<now()-interval '90 days' AND NOT EXISTS(SELECT 1 FROM nikon_community_risk_windows WHERE audit_id=nikon_community_audit.id) ORDER BY id LIMIT 1000);
 DELETE FROM nikon_auth_challenges WHERE id IN (SELECT id FROM nikon_auth_challenges WHERE expires_at<now()-interval '1 day' ORDER BY expires_at LIMIT 1000);
 DELETE FROM nikon_abuse_windows WHERE key IN (SELECT key FROM nikon_abuse_windows WHERE window_start<now()-interval '2 days' ORDER BY window_start LIMIT 1000);
 DELETE FROM nikon_community_activity WHERE id IN (SELECT id FROM nikon_community_activity WHERE created_at<now()-interval '35 days' ORDER BY id LIMIT 1000);
END; $$;
CREATE OR REPLACE FUNCTION public.nikon_abuse_allowed(p_scope text,p_source_hash text,p_installation_hash text)
RETURNS boolean LANGUAGE plpgsql SECURITY INVOKER SET search_path=public,pg_temp AS $$
DECLARE a boolean; b boolean; c boolean; lim integer;
BEGIN
 IF p_source_hash IS NULL OR p_source_hash !~ '^[a-f0-9]{64}$' OR p_installation_hash IS NULL OR p_installation_hash !~ '^[a-f0-9]{64}$'
 OR p_scope NOT IN('challenge-issue','challenge-consume','register','password-login','qq-login','community-upload','community-comment','community-write') THEN RAISE EXCEPTION 'INVALID_CHALLENGE'; END IF;
 lim:=CASE p_scope WHEN 'challenge-issue' THEN 30 WHEN 'challenge-consume' THEN 20 WHEN 'register' THEN 8 WHEN 'community-upload' THEN 40 WHEN 'community-comment' THEN 120 WHEN 'community-write' THEN 180 ELSE 60 END;
 -- Consistent lock ordering: global, source, installation. Each rejection still increments all dimensions.
 a:=nikon_abuse_tick(p_scope||':global',CASE WHEN p_scope='register' THEN 120 ELSE 1200 END,900);
 IF NOT a THEN PERFORM nikon_community_risk(p_scope,'environment'); RETURN false; END IF;
 b:=nikon_abuse_tick(p_scope||':source:'||p_source_hash,lim,900);
 c:=nikon_abuse_tick(p_scope||':installation:'||p_installation_hash,CASE WHEN p_scope='register' THEN 3 ELSE lim END,900);
 IF NOT(b AND c) THEN PERFORM nikon_community_risk(p_scope,p_source_hash); END IF;
 PERFORM nikon_community_cleanup();
 RETURN a AND b AND c;
END; $$;
CREATE OR REPLACE FUNCTION public.nikon_issue_challenge(p_id uuid,p_answer_hash text,p_source_hash text,p_installation_hash text,p_expires_at timestamptz)
RETURNS boolean LANGUAGE plpgsql SECURITY INVOKER SET search_path=public,pg_temp AS $$
BEGIN
 IF NOT nikon_abuse_allowed('challenge-issue',p_source_hash,p_installation_hash) THEN RETURN false; END IF;
 IF p_id IS NULL OR p_answer_hash IS NULL OR p_answer_hash !~ '^[a-f0-9]{64}$' OR p_expires_at IS NULL OR p_expires_at<=now() OR p_expires_at>now()+interval '130 seconds' THEN RAISE EXCEPTION 'INVALID_CHALLENGE'; END IF;
 INSERT INTO nikon_auth_challenges(id,answer_hash,source_hash,installation_hash,expires_at) VALUES(p_id,p_answer_hash,p_source_hash,p_installation_hash,p_expires_at);
 RETURN true;
END; $$;
CREATE OR REPLACE FUNCTION public.nikon_consume_challenge(p_id uuid,p_answer_hash text,p_source_hash text,p_installation_hash text,p_purpose text)
RETURNS boolean LANGUAGE plpgsql SECURITY INVOKER SET search_path=public,pg_temp AS $$
DECLARE challenge nikon_auth_challenges%ROWTYPE; allowed boolean;
BEGIN
 allowed:=nikon_abuse_allowed('challenge-consume',p_source_hash,p_installation_hash);
 IF p_purpose NOT IN('register','qq_register') THEN RETURN false; END IF;
 SELECT * INTO challenge FROM nikon_auth_challenges WHERE id=p_id FOR UPDATE;
 IF NOT FOUND OR challenge.consumed_at IS NOT NULL THEN RETURN false; END IF;
 -- A wrong answer consumes the ticket. Guessing cannot race across instances.
 UPDATE nikon_auth_challenges SET consumed_at=now() WHERE id=p_id;
 RETURN allowed AND challenge.expires_at>now() AND challenge.answer_hash=p_answer_hash AND challenge.source_hash=p_source_hash AND challenge.installation_hash=p_installation_hash;
END; $$;

CREATE OR REPLACE FUNCTION public.nikon_community_author(p_user_id uuid)
RETURNS jsonb LANGUAGE sql STABLE SECURITY INVOKER SET search_path=public,pg_temp AS $$
 SELECT jsonb_build_object('id',u.id,'nickname',u.nickname,'avatarUrl',u.avatar_url) FROM nikon_users u WHERE u.id=p_user_id;
$$;
CREATE OR REPLACE FUNCTION public.nikon_community_public_rows()
RETURNS TABLE(id uuid,item jsonb,published_at timestamptz) LANGUAGE sql STABLE SECURITY INVOKER SET search_path=public,pg_temp AS $$
 SELECT s.id,(s.published-'file'-'preview'-'sortOrder'-'rightsConfirmed')||jsonb_build_object('id',s.id,'category',coalesce(s.published->>'category',''),'revision',s.published_revision,'source','community','author',nikon_community_author(s.user_id),
 'fileUrl',s.published->'file'->>'url','sha256',s.published->'file'->>'sha256','byteLength',s.published->'file'->'byteLength','previewUrl',coalesce(s.published->'preview'->>'url',''),'publishedAt',s.published_at),s.published_at
 FROM nikon_community_submissions s WHERE s.published IS NOT NULL
 UNION ALL
 SELECT p.id,(p.published-'file'-'preview'-'sortOrder')||jsonb_build_object('id',p.id,'category',coalesce(p.published->>'category',''),'revision',p.published_revision,'source','curated','author',jsonb_build_object('id','','nickname','Huevia','avatarUrl',''),
 'fileUrl',p.published->'file'->>'url','sha256',p.published->'file'->>'sha256','byteLength',p.published->'file'->'byteLength','previewUrl',coalesce(p.published->'preview'->>'url',''),'publishedAt',p.published_at),p.published_at
 FROM nikon_presets p WHERE p.status='published';
$$;
CREATE OR REPLACE FUNCTION public.nikon_community_submission_view(p_id uuid)
RETURNS jsonb LANGUAGE sql STABLE SECURITY INVOKER SET search_path=public,pg_temp AS $$
 SELECT s.draft||jsonb_build_object('id',s.id,'author',nikon_community_author(s.user_id),'revision',s.revision,'status',s.status,'reason',s.reason,
 'publishedRevision',s.published_revision,'hasUnpublishedChanges',s.published IS NOT NULL AND s.published_revision<>s.revision,
 'publishedAt',s.published_at,'createdAt',s.created_at,'updatedAt',s.updated_at,'fileAssetId',s.draft->'file'->>'id','previewAssetId',s.draft->'preview'->>'id')
 FROM nikon_community_submissions s WHERE s.id=p_id;
$$;
CREATE OR REPLACE FUNCTION public.nikon_community_comment_view(p_id uuid)
RETURNS jsonb LANGUAGE sql STABLE SECURITY INVOKER SET search_path=public,pg_temp AS $$
 SELECT jsonb_build_object('id',c.id,'presetId',c.preset_id,'parentId',c.parent_id,'text',CASE WHEN c.status='visible' THEN c.text ELSE '' END,
 'author',nikon_community_author(c.user_id),'status',c.status,'createdAt',c.created_at,'updatedAt',c.updated_at)
 FROM nikon_community_comments c WHERE c.id=p_id;
$$;

-- Only the service role reaches this dispatcher. Public callers never choose an actor/user id.
CREATE OR REPLACE FUNCTION public.nikon_community(p_action text,p_user_id uuid DEFAULT NULL,p_id uuid DEFAULT NULL,p_body jsonb DEFAULT '{}'::jsonb)
RETURNS jsonb LANGUAGE plpgsql SECURITY INVOKER SET search_path=public,pg_temp AS $$
DECLARE account nikon_users%ROWTYPE; restriction nikon_community_restrictions%ROWTYPE; submission nikon_community_submissions%ROWTYPE;
 comment nikon_community_comments%ROWTYPE; parent nikon_community_comments%ROWTYPE; cfg jsonb; previous_cfg jsonb; result jsonb; items jsonb; n bigint;
 page_num integer:=coalesce((p_body->>'page')::integer,1); page_size integer:=coalesce((p_body->>'pageSize')::integer,20);
 asset jsonb; preview jsonb; snapshot jsonb; action_key text; request_key text; old_hash text; budget integer; target_parent uuid; new_id uuid; new_comment_status text; is_admin boolean:=left(p_action,5)='admin';
BEGIN
 IF p_action IS NULL OR p_body IS NULL OR jsonb_typeof(p_body)<>'object' OR page_num NOT BETWEEN 1 AND 100000 OR page_size NOT BETWEEN 1 AND 50 THEN RAISE EXCEPTION 'INVALID_COMMUNITY'; END IF;
 SELECT settings INTO cfg FROM nikon_community_settings WHERE singleton;
 IF p_user_id IS NOT NULL THEN
  SELECT * INTO account FROM nikon_users WHERE id=p_user_id FOR NO KEY UPDATE;
  IF NOT FOUND OR account.status<>'active' THEN RAISE EXCEPTION 'USER_DISABLED'; END IF;
  SELECT * INTO restriction FROM nikon_community_restrictions WHERE user_id=p_user_id;
 END IF;
 IF p_action IN('save','submit','reserveUpload','addAsset','comment','report','deleteComment','read','readAll') THEN
  IF p_user_id IS NULL THEN RAISE EXCEPTION 'USER_LOGIN_REQUIRED'; END IF;
  IF p_action='comment' AND restriction.muted_until>now() THEN RAISE EXCEPTION 'USER_MUTED'; END IF;
 END IF;
 IF p_action IN('save','submit','reserveUpload','addAsset') AND restriction.submission_blocked_until>now() THEN RAISE EXCEPTION 'SUBMISSION_BLOCKED'; END IF;
 IF p_action IN('save','submit','comment','report') THEN
  request_key:=p_body->>'idempotencyKey'; action_key:=p_action||':'||coalesce(p_id::text,'new');
  IF request_key IS NULL OR request_key !~ '^[A-Za-z0-9_-]{8,96}$' THEN RAISE EXCEPTION 'IDEMPOTENCY_REQUIRED'; END IF;
  SELECT request_hash,i.result INTO old_hash,result FROM nikon_community_idempotency i WHERE user_id=p_user_id AND action=action_key AND key=request_key;
  IF FOUND THEN
   IF old_hash<>md5(p_body::text) THEN RAISE EXCEPTION 'IDEMPOTENCY_CONFLICT'; END IF;
   IF p_action='comment' THEN
    SELECT * INTO comment FROM nikon_community_comments WHERE id=(result->>'id')::uuid;
    IF NOT FOUND OR NOT EXISTS(SELECT 1 FROM nikon_community_public_rows() WHERE id=comment.preset_id)
     OR (comment.parent_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM nikon_community_comments WHERE id=comment.parent_id AND status='visible')) THEN RAISE EXCEPTION 'COMMENT_UNAVAILABLE'; END IF;
    RETURN nikon_community_comment_view(comment.id);
   END IF;
   RETURN result;
  END IF;
 END IF;
 IF p_action='feed' THEN
  SELECT count(*) INTO n FROM nikon_community_public_rows() r WHERE coalesce(p_body->>'category','')='' OR coalesce(r.item->>'category','')=CASE WHEN p_body->>'category'='__uncategorized__' THEN '' ELSE p_body->>'category' END;
  SELECT coalesce(jsonb_agg(t.item ORDER BY t.published_at DESC,t.id),'[]'::jsonb) INTO items FROM (
   SELECT * FROM nikon_community_public_rows() r WHERE coalesce(p_body->>'category','')='' OR coalesce(r.item->>'category','')=CASE WHEN p_body->>'category'='__uncategorized__' THEN '' ELSE p_body->>'category' END
   ORDER BY published_at DESC,id LIMIT page_size OFFSET (page_num::bigint-1)*page_size) t;
  RETURN jsonb_build_object('items',items,'total',n,'page',page_num,'pageSize',page_size,'hasMore',page_num::bigint*page_size<n,
   'categories',(SELECT coalesce(jsonb_agg(category ORDER BY category),'[]'::jsonb) FROM (SELECT DISTINCT coalesce(item->>'category','') category FROM nikon_community_public_rows() ORDER BY category LIMIT 100) cats));
 ELSIF p_action='detail' THEN
  SELECT r.item INTO result FROM nikon_community_public_rows() r WHERE id=p_id;
  IF result IS NULL THEN RAISE EXCEPTION 'NOT_FOUND'; END IF; RETURN result;
 ELSIF p_action IN('publicAsset','ownAsset','adminAsset') THEN
  IF p_action='publicAsset' THEN
   SELECT a.payload INTO result FROM nikon_community_assets a WHERE a.id=p_body->>'assetId' AND EXISTS(
    SELECT 1 FROM nikon_community_submissions s WHERE s.published IS NOT NULL AND (s.published->'file'->>'id'=a.id OR s.published->'preview'->>'id'=a.id)) LIMIT 1;
  ELSE SELECT payload INTO result FROM nikon_community_assets WHERE id=p_body->>'assetId' AND (p_action='adminAsset' OR user_id=p_user_id) LIMIT 1; END IF;
  RETURN result;
 ELSIF p_action='reserveUpload' THEN
  budget:=(cfg->>CASE WHEN account.created_at>now()-interval '1 day' THEN 'newDayUploads' ELSE 'dailyUploads' END)::integer;
  IF (SELECT count(*) FROM nikon_community_activity WHERE user_id=p_user_id AND kind='upload' AND created_at>=date_trunc('day',now() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC')>=budget THEN RAISE EXCEPTION 'UPLOAD_QUOTA'; END IF;
  INSERT INTO nikon_community_activity(user_id,kind) VALUES(p_user_id,'upload'); RETURN '{"ok":true}'::jsonb;
 ELSIF p_action='addAsset' THEN
  asset:=p_body->'asset';
  IF asset->>'id' IS NULL OR asset->>'id' !~ '^(ncp|preview)-[a-f0-9]{64}$' OR (asset->>'byteLength')::integer NOT BETWEEN 1 AND 4194304
   OR (asset->>'kind'='ncp' AND ((asset->>'byteLength')::integer>1048576 OR asset->>'ncpVersion'<>'0100')) THEN RAISE EXCEPTION 'INVALID_FILE'; END IF;
  INSERT INTO nikon_community_assets(user_id,id,payload) VALUES(p_user_id,asset->>'id',asset) ON CONFLICT(user_id,id) DO NOTHING;
  SELECT payload INTO result FROM nikon_community_assets WHERE user_id=p_user_id AND id=asset->>'id'; RETURN result;
 ELSIF p_action IN('mine','adminSubmissions') THEN
  SELECT count(*) INTO n FROM nikon_community_submissions s WHERE (is_admin OR s.user_id=p_user_id) AND (coalesce(p_body->>'status','all')='all' OR s.status=p_body->>'status');
  SELECT coalesce(jsonb_agg(t.item ORDER BY t.updated_at DESC,t.id),'[]'::jsonb) INTO items FROM (
   SELECT s.id,s.updated_at,nikon_community_submission_view(s.id) item FROM nikon_community_submissions s WHERE (is_admin OR s.user_id=p_user_id)
   AND (coalesce(p_body->>'status','all')='all' OR s.status=p_body->>'status') ORDER BY s.updated_at DESC,s.id LIMIT page_size OFFSET (page_num::bigint-1)*page_size) t;
  RETURN jsonb_build_object('items',items,'total',n,'page',page_num,'pageSize',page_size);
 ELSIF p_action IN('ownDetail','adminSubmission','versions','adminVersions') THEN
  SELECT * INTO submission FROM nikon_community_submissions WHERE id=p_id AND (is_admin OR user_id=p_user_id);
  IF NOT FOUND THEN RAISE EXCEPTION 'NOT_FOUND'; END IF;
  IF p_action IN('versions','adminVersions') THEN SELECT coalesce(jsonb_agg(jsonb_build_object('revision',v.revision,'snapshot',v.snapshot,'createdAt',v.created_at) ORDER BY v.revision DESC),'[]'::jsonb) INTO items FROM (SELECT * FROM nikon_community_versions WHERE preset_id=p_id ORDER BY revision DESC LIMIT page_size OFFSET (page_num::bigint-1)*page_size) v; RETURN jsonb_build_object('items',items,'total',(SELECT count(*) FROM nikon_community_versions WHERE preset_id=p_id),'page',page_num,'pageSize',page_size); END IF;
  RETURN nikon_community_submission_view(p_id);
 ELSIF p_action IN('save','submit') THEN
  IF p_id IS NOT NULL THEN SELECT * INTO submission FROM nikon_community_submissions WHERE id=p_id AND user_id=p_user_id FOR UPDATE; IF NOT FOUND THEN RAISE EXCEPTION 'NOT_FOUND'; END IF;
   IF submission.revision IS DISTINCT FROM (p_body->>'expectedRevision')::integer THEN RAISE EXCEPTION 'REVISION_CONFLICT'; END IF; END IF;
  IF p_action='save' THEN
   IF p_id IS NULL AND (SELECT count(*) FROM nikon_community_submissions WHERE user_id=p_user_id AND published IS NULL)>=20 THEN RAISE EXCEPTION 'DRAFT_QUOTA'; END IF;
   IF p_body->>'name' IS NULL OR length(trim(p_body->>'name')) NOT BETWEEN 1 AND 160 OR length(coalesce(p_body->>'description',''))>2000 OR length(coalesce(p_body->>'category',''))>24 THEN RAISE EXCEPTION 'INVALID_PRESET'; END IF;
   SELECT payload INTO asset FROM nikon_community_assets WHERE user_id=p_user_id AND id=p_body->>'fileAssetId' AND payload->>'kind'='ncp';
   IF asset IS NULL THEN RAISE EXCEPTION 'INVALID_FILE'; END IF;
   IF EXISTS(SELECT 1 FROM nikon_community_submissions s WHERE s.user_id=p_user_id AND (p_id IS NULL OR s.id<>p_id)
    AND (s.draft->'file'->>'sha256'=asset->>'sha256' OR s.published->'file'->>'sha256'=asset->>'sha256')) THEN RAISE EXCEPTION 'DUPLICATE_SUBMISSION'; END IF;
   IF p_body->>'previewAssetId' IS NOT NULL THEN SELECT payload INTO preview FROM nikon_community_assets WHERE user_id=p_user_id AND id=p_body->>'previewAssetId' AND payload->>'kind'='preview'; IF preview IS NULL THEN RAISE EXCEPTION 'INVALID_PREVIEW'; END IF; END IF;
   snapshot:=(p_body-'fileAssetId'-'previewAssetId'-'expectedRevision'-'submit'-'idempotencyKey')||jsonb_build_object('file',asset,'preview',preview);
   IF p_id IS NULL THEN INSERT INTO nikon_community_submissions(user_id,draft) VALUES(p_user_id,snapshot) RETURNING * INTO submission;
   ELSE UPDATE nikon_community_submissions SET draft=snapshot,revision=revision+1,status='draft',reason='',updated_at=clock_timestamp() WHERE id=p_id RETURNING * INTO submission; END IF;
   INSERT INTO nikon_community_versions(preset_id,revision,snapshot) VALUES(submission.id,submission.revision,submission.draft);
  END IF;
  IF p_action='submit' OR coalesce((p_body->>'submit')::boolean,false) THEN
   IF submission.status='pending' THEN RAISE EXCEPTION 'ALREADY_PENDING'; END IF;
   budget:=(cfg->>CASE WHEN account.created_at>now()-interval '1 day' THEN 'newDaySubmissions' ELSE 'dailySubmissions' END)::integer;
   IF (SELECT count(*) FROM nikon_community_activity WHERE user_id=p_user_id AND kind='submit' AND created_at>=date_trunc('day',now() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC')>=budget THEN RAISE EXCEPTION 'SUBMISSION_QUOTA'; END IF;
   IF (SELECT count(*) FROM nikon_community_submissions WHERE user_id=p_user_id AND status='pending')>=(cfg->>'pendingSubmissions')::integer THEN RAISE EXCEPTION 'PENDING_QUOTA'; END IF;
   UPDATE nikon_community_submissions SET status='pending',reason='',updated_at=clock_timestamp() WHERE id=submission.id;
   INSERT INTO nikon_community_activity(user_id,kind) VALUES(p_user_id,'submit');
  END IF;
  result:=nikon_community_submission_view(submission.id);
 ELSIF p_action='adminReviewSubmission' THEN
  SELECT * INTO submission FROM nikon_community_submissions WHERE id=p_id FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'NOT_FOUND'; END IF;
  IF submission.revision IS DISTINCT FROM (p_body->>'expectedRevision')::integer THEN RAISE EXCEPTION 'REVISION_CONFLICT'; END IF;
  IF p_body->>'decision' IN('approve','reject') AND submission.status<>'pending' THEN RAISE EXCEPTION 'NOT_PENDING'; END IF;
  IF p_body->>'decision'='approve' THEN UPDATE nikon_community_submissions SET status='published',published=draft,published_revision=revision,published_at=clock_timestamp(),reason='',updated_at=clock_timestamp() WHERE id=p_id;
  ELSIF p_body->>'decision'='reject' THEN UPDATE nikon_community_submissions SET status='rejected',reason=coalesce(p_body->>'reason',''),updated_at=clock_timestamp() WHERE id=p_id;
  ELSIF p_body->>'decision'='withdraw' THEN UPDATE nikon_community_submissions SET status='withdrawn',published=NULL,published_revision=NULL,reason=coalesce(p_body->>'reason',''),updated_at=clock_timestamp() WHERE id=p_id;
  ELSE RAISE EXCEPTION 'INVALID_COMMUNITY'; END IF;
  result:=nikon_community_submission_view(p_id);
  INSERT INTO nikon_community_messages(user_id,type,submission_id,revision,review_reason)
   VALUES(submission.user_id,CASE p_body->>'decision' WHEN 'approve' THEN 'review_approved' WHEN 'reject' THEN 'review_rejected' ELSE 'review_withdrawn' END,
   p_id,submission.revision,coalesce(p_body->>'reason','')) ON CONFLICT(submission_id,revision,type) DO NOTHING;
 ELSIF p_action='replies' THEN
  SELECT * INTO parent FROM nikon_community_comments WHERE id=p_id AND parent_id IS NULL AND (status='visible' OR (status='deleted' AND was_public));
  IF NOT FOUND OR NOT EXISTS(SELECT 1 FROM nikon_community_public_rows() WHERE id=parent.preset_id) THEN RAISE EXCEPTION 'COMMENT_UNAVAILABLE'; END IF;
  SELECT count(*) INTO n FROM nikon_community_comments WHERE parent_id=p_id AND (status='visible' OR (status='deleted' AND was_public));
  SELECT coalesce(jsonb_agg(nikon_community_comment_view(t.id) ORDER BY t.created_at,t.id),'[]'::jsonb) INTO items FROM (SELECT * FROM nikon_community_comments WHERE parent_id=p_id AND (status='visible' OR (status='deleted' AND was_public)) ORDER BY created_at,id LIMIT page_size OFFSET (page_num::bigint-1)*page_size) t;
  RETURN jsonb_build_object('items',items,'total',n,'page',page_num,'pageSize',page_size,'hasMore',page_num::bigint*page_size<n);
 ELSIF p_action IN('comments','comment') THEN
  IF NOT EXISTS(SELECT 1 FROM nikon_community_public_rows() WHERE id=p_id) THEN RAISE EXCEPTION 'NOT_FOUND'; END IF;
  IF p_action='comments' THEN
   IF p_body->>'focusCommentId' IS NOT NULL THEN
    SELECT * INTO comment FROM nikon_community_comments WHERE id=(p_body->>'focusCommentId')::uuid AND preset_id=p_id AND status='visible';
    IF FOUND THEN
     SELECT * INTO parent FROM nikon_community_comments WHERE id=coalesce(comment.parent_id,comment.id) AND status='visible';
     IF FOUND THEN
      result:=nikon_community_comment_view(parent.id)||jsonb_build_object('replyCount',(SELECT count(*) FROM nikon_community_comments r WHERE r.parent_id=parent.id AND (r.status='visible' OR (r.status='deleted' AND r.was_public))),
       'replies',coalesce((SELECT jsonb_agg(nikon_community_comment_view(r.id) ORDER BY r.created_at,r.id) FROM (SELECT * FROM nikon_community_comments nested WHERE nested.parent_id=parent.id AND (nested.status='visible' OR (nested.status='deleted' AND nested.was_public)) ORDER BY CASE WHEN nested.id=comment.id THEN 0 ELSE 1 END,nested.created_at,nested.id LIMIT 3) r),'[]'::jsonb));
      RETURN jsonb_build_object('items',jsonb_build_array(result),'total',1,'page',1,'pageSize',page_size,'focusAvailable',true,'focusCommentId',comment.id);
     END IF;
    END IF;
    RETURN jsonb_build_object('items','[]'::jsonb,'total',0,'page',1,'pageSize',page_size,'focusAvailable',false,'focusCommentId',p_body->>'focusCommentId');
   END IF;
   SELECT count(*) INTO n FROM nikon_community_comments c WHERE preset_id=p_id AND parent_id IS NULL AND (status='visible' OR (status='deleted' AND was_public));
   SELECT coalesce(jsonb_agg(t.item ORDER BY t.created_at,t.id),'[]'::jsonb) INTO items FROM (
    SELECT c.id,c.created_at,nikon_community_comment_view(c.id)||jsonb_build_object('replyCount',(SELECT count(*) FROM nikon_community_comments r WHERE r.parent_id=c.id AND (r.status='visible' OR (r.status='deleted' AND r.was_public))),
     'replies',coalesce((SELECT jsonb_agg(nikon_community_comment_view(r.id) ORDER BY r.created_at,r.id) FROM (SELECT * FROM nikon_community_comments nested WHERE nested.parent_id=c.id AND (nested.status='visible' OR (nested.status='deleted' AND nested.was_public)) ORDER BY nested.created_at,nested.id LIMIT 3) r),'[]'::jsonb)) item
    FROM nikon_community_comments c WHERE c.preset_id=p_id AND c.parent_id IS NULL AND (c.status='visible' OR (c.status='deleted' AND c.was_public)) ORDER BY c.created_at,c.id LIMIT page_size OFFSET (page_num::bigint-1)*page_size) t;
   RETURN jsonb_build_object('items',items,'total',n,'page',page_num,'pageSize',page_size);
  END IF;
  IF p_body->>'text' IS NULL OR length(trim(p_body->>'text')) NOT BETWEEN 1 AND 500 THEN RAISE EXCEPTION 'INVALID_TEXT'; END IF;
  target_parent:=(p_body->>'parentId')::uuid;
  IF target_parent IS NOT NULL THEN SELECT * INTO parent FROM nikon_community_comments WHERE id=target_parent AND preset_id=p_id AND status='visible' AND parent_id IS NULL FOR UPDATE; IF NOT FOUND THEN RAISE EXCEPTION 'COMMENT_UNAVAILABLE'; END IF; END IF;
  budget:=(cfg->>CASE WHEN account.created_at>now()-interval '1 day' THEN 'newDayComments' ELSE 'dailyComments' END)::integer;
  IF (SELECT count(*) FROM nikon_community_activity WHERE user_id=p_user_id AND kind='comment' AND created_at>=date_trunc('day',now() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC')>=budget THEN RAISE EXCEPTION 'COMMENT_QUOTA'; END IF;
  IF EXISTS(SELECT 1 FROM nikon_community_activity WHERE user_id=p_user_id AND kind='comment' AND created_at>now()-make_interval(secs=>(cfg->>'commentIntervalSeconds')::integer)) THEN RAISE EXCEPTION 'COMMENT_TOO_FAST'; END IF;
  -- During the first 24 hours, posting remains moderated until this account has an approved visible comment.
  new_comment_status:=CASE WHEN account.created_at>now()-interval '1 day' AND NOT EXISTS(SELECT 1 FROM nikon_community_comments WHERE user_id=p_user_id AND status='visible') THEN 'pending' ELSE 'visible' END;
  INSERT INTO nikon_community_comments(preset_id,user_id,parent_id,text,status,was_public) VALUES(p_id,p_user_id,target_parent,p_body->>'text',new_comment_status,new_comment_status='visible') RETURNING id INTO new_id;
  INSERT INTO nikon_community_activity(user_id,kind) VALUES(p_user_id,'comment');
  IF new_comment_status='visible' AND target_parent IS NOT NULL AND parent.user_id<>p_user_id THEN INSERT INTO nikon_community_messages(user_id,comment_id,parent_id) VALUES(parent.user_id,new_id,target_parent) ON CONFLICT(comment_id) DO NOTHING; END IF;
  result:=nikon_community_comment_view(new_id);
 ELSIF p_action='deleteComment' THEN
  UPDATE nikon_community_comments SET status='deleted',text='[deleted]',updated_at=clock_timestamp() WHERE id=p_id AND user_id=p_user_id;
  IF NOT FOUND THEN RAISE EXCEPTION 'NOT_FOUND'; END IF; RETURN '{"ok":true}'::jsonb;
 ELSIF p_action='report' THEN
  SELECT * INTO comment FROM nikon_community_comments WHERE id=p_id AND status='visible';
  IF NOT FOUND OR NOT EXISTS(SELECT 1 FROM nikon_community_public_rows() WHERE id=comment.preset_id)
   OR (comment.parent_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM nikon_community_comments WHERE id=comment.parent_id AND status='visible')) THEN RAISE EXCEPTION 'COMMENT_UNAVAILABLE'; END IF;
  IF (SELECT count(*) FROM nikon_community_activity WHERE user_id=p_user_id AND kind='report' AND created_at>now()-interval '1 day')>=10 THEN RAISE EXCEPTION 'REPORT_QUOTA'; END IF;
  INSERT INTO nikon_community_reports(comment_id,user_id,reason) VALUES(p_id,p_user_id,p_body->>'reason') ON CONFLICT(comment_id,user_id) DO NOTHING;
  INSERT INTO nikon_community_activity(user_id,kind) VALUES(p_user_id,'report'); result:='{"ok":true}'::jsonb;
 ELSIF p_action IN('messages','unread','read','readAll') THEN
  IF p_user_id IS NULL THEN RAISE EXCEPTION 'USER_LOGIN_REQUIRED'; END IF;
  IF p_action IN('read','readAll') THEN UPDATE nikon_community_messages SET read_at=coalesce(read_at,clock_timestamp()) WHERE user_id=p_user_id AND (p_action='readAll' OR id=p_id); RETURN '{"ok":true}'::jsonb; END IF;
  SELECT count(*) INTO n FROM nikon_community_messages WHERE user_id=p_user_id AND read_at IS NULL;
  IF p_action='unread' THEN RETURN jsonb_build_object('unread',n,'count',n); END IF;
  SELECT coalesce(jsonb_agg(t.item ORDER BY t.created_at DESC,t.id),'[]'::jsonb) INTO items FROM (
   SELECT m.id,m.created_at,jsonb_build_object('id',m.id,'type',m.type,'presetId',c.preset_id,'commentId',c.id,'parentId',m.parent_id,
    'submissionId',m.submission_id,'revision',m.revision,
    'available',CASE WHEN m.type='reply' THEN c.status='visible' AND par.status='visible' AND r.id IS NOT NULL ELSE s.id IS NOT NULL END,
    'text',CASE WHEN m.type<>'reply' THEN m.review_reason WHEN c.status='visible' AND par.status='visible' AND r.id IS NOT NULL THEN c.text ELSE '' END,
    'reason',CASE WHEN m.type='reply' THEN '' ELSE m.review_reason END,
    'presetName',CASE WHEN m.type<>'reply' THEN coalesce(s.draft->>'name','') WHEN c.status='visible' AND par.status='visible' THEN coalesce(r.item->>'name','') ELSE '' END,
    'author',CASE WHEN c.status='visible' AND par.status='visible' AND r.id IS NOT NULL THEN nikon_community_author(c.user_id) ELSE NULL END,
    'read',m.read_at IS NOT NULL,'readAt',m.read_at,'createdAt',m.created_at) item
   FROM nikon_community_messages m LEFT JOIN nikon_community_comments c ON c.id=m.comment_id LEFT JOIN nikon_community_comments par ON par.id=m.parent_id
   LEFT JOIN nikon_community_submissions s ON s.id=m.submission_id AND s.user_id=p_user_id
   LEFT JOIN nikon_community_public_rows() r ON r.id=c.preset_id WHERE m.user_id=p_user_id ORDER BY m.created_at DESC,m.id LIMIT page_size OFFSET (page_num::bigint-1)*page_size) t;
  RETURN jsonb_build_object('items',items,'unread',n,'total',(SELECT count(*) FROM nikon_community_messages WHERE user_id=p_user_id),'page',page_num,'pageSize',page_size);
 ELSIF p_action IN('adminComments','adminReports','adminAudit') THEN
  IF p_action='adminComments' THEN SELECT coalesce(jsonb_agg(t.item ORDER BY t.created_at DESC),'[]'::jsonb) INTO items FROM (SELECT to_jsonb(c)||jsonb_build_object('author',nikon_community_author(c.user_id)) item,c.created_at FROM nikon_community_comments c WHERE coalesce(p_body->>'status','all')='all' OR c.status=p_body->>'status' ORDER BY c.created_at DESC,c.id LIMIT page_size OFFSET (page_num::bigint-1)*page_size)t; SELECT count(*) INTO n FROM nikon_community_comments WHERE coalesce(p_body->>'status','all')='all' OR status=p_body->>'status';
  ELSIF p_action='adminReports' THEN SELECT coalesce(jsonb_agg(t.item ORDER BY t.created_at DESC),'[]'::jsonb) INTO items FROM (SELECT to_jsonb(r)||jsonb_build_object('comment',nikon_community_comment_view(r.comment_id)) item,r.created_at FROM nikon_community_reports r WHERE coalesce(p_body->>'status','all')='all' OR r.status=p_body->>'status' ORDER BY r.created_at DESC,r.id LIMIT page_size OFFSET (page_num::bigint-1)*page_size)t; SELECT count(*) INTO n FROM nikon_community_reports WHERE coalesce(p_body->>'status','all')='all' OR status=p_body->>'status';
  ELSE SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY t.created_at DESC),'[]'::jsonb) INTO items FROM (SELECT * FROM nikon_community_audit ORDER BY created_at DESC,id DESC LIMIT page_size OFFSET (page_num::bigint-1)*page_size)t; SELECT count(*) INTO n FROM nikon_community_audit; END IF;
  RETURN jsonb_build_object('items',items,'total',n,'page',page_num,'pageSize',page_size);
 ELSIF p_action IN('adminComment','adminReport') THEN
  IF p_action='adminComment' THEN SELECT to_jsonb(c) INTO result FROM nikon_community_comments c WHERE id=p_id; ELSE SELECT to_jsonb(r) INTO result FROM nikon_community_reports r WHERE id=p_id; END IF;
  IF result IS NULL THEN RAISE EXCEPTION 'NOT_FOUND'; END IF; RETURN result;
 ELSIF p_action='adminReviewComment' THEN
  IF p_body->>'decision' IS NULL OR p_body->>'decision' NOT IN('hide','restore') THEN RAISE EXCEPTION 'INVALID_COMMUNITY'; END IF;
  SELECT * INTO comment FROM nikon_community_comments WHERE id=p_id AND status<>'deleted' FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'NOT_FOUND'; END IF;
  IF p_body->>'decision'='restore' THEN
   IF NOT EXISTS(SELECT 1 FROM nikon_community_public_rows() WHERE id=comment.preset_id) THEN RAISE EXCEPTION 'COMMENT_UNAVAILABLE'; END IF;
   IF comment.parent_id IS NOT NULL THEN
    SELECT * INTO parent FROM nikon_community_comments WHERE id=comment.parent_id AND status='visible' FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'COMMENT_UNAVAILABLE'; END IF;
   END IF;
  END IF;
  UPDATE nikon_community_comments SET status=CASE WHEN p_body->>'decision'='hide' THEN 'hidden' ELSE 'visible' END,
   was_public=was_public OR p_body->>'decision'='restore',reason=coalesce(p_body->>'reason',''),updated_at=clock_timestamp() WHERE id=p_id;
  IF p_body->>'decision'='restore' AND comment.parent_id IS NOT NULL AND parent.user_id<>comment.user_id THEN
   INSERT INTO nikon_community_messages(user_id,comment_id,parent_id) VALUES(parent.user_id,comment.id,comment.parent_id) ON CONFLICT(comment_id) DO NOTHING;
  END IF;
  result:=nikon_community_comment_view(p_id);
 ELSIF p_action='adminReviewReport' THEN
  UPDATE nikon_community_reports SET status=CASE WHEN p_body->>'decision'='resolve' THEN 'resolved' ELSE 'dismissed' END,review_reason=coalesce(p_body->>'reason','') WHERE id=p_id;
  IF NOT FOUND THEN RAISE EXCEPTION 'NOT_FOUND'; END IF; result:='{"ok":true}'::jsonb;
 ELSIF p_action IN('adminRestrictions','adminSetRestrictions') THEN
  IF NOT EXISTS(SELECT 1 FROM nikon_users WHERE id=p_id) THEN RAISE EXCEPTION 'USER_NOT_FOUND'; END IF;
  IF p_action='adminSetRestrictions' THEN
   -- Lock the same account row as community writes to serialize restrictions with all mutations.
   PERFORM 1 FROM nikon_users WHERE id=p_id FOR NO KEY UPDATE;
   INSERT INTO nikon_community_restrictions(user_id,muted_until,submission_blocked_until,reason) VALUES(p_id,(p_body->>'mutedUntil')::timestamptz,(p_body->>'submissionBlockedUntil')::timestamptz,coalesce(p_body->>'reason','')) ON CONFLICT(user_id) DO UPDATE SET muted_until=excluded.muted_until,submission_blocked_until=excluded.submission_blocked_until,reason=excluded.reason,updated_at=clock_timestamp();
  END IF;
  SELECT jsonb_build_object('userId',user_id,'mutedUntil',muted_until,'submissionBlockedUntil',submission_blocked_until,'reason',reason,'updatedAt',updated_at) INTO result FROM nikon_community_restrictions WHERE user_id=p_id;
  result:=coalesce(result,jsonb_build_object('userId',p_id,'mutedUntil',NULL,'submissionBlockedUntil',NULL,'reason',''));
 ELSIF p_action IN('adminSettings','adminSetSettings') THEN
  IF p_action='adminSetSettings' THEN
   SELECT settings INTO cfg FROM nikon_community_settings WHERE singleton FOR UPDATE;
   previous_cfg:=cfg;
   IF jsonb_typeof(p_body->'settings')<>'object' THEN RAISE EXCEPTION 'INVALID_COMMUNITY'; END IF;
   FOR action_key IN SELECT jsonb_object_keys(p_body->'settings') LOOP
    IF NOT cfg ? action_key OR jsonb_typeof(p_body->'settings'->action_key)<>'number' OR (p_body->'settings'->>action_key) !~ '^[0-9]{1,4}$' OR (p_body->'settings'->>action_key)::integer>1000 THEN RAISE EXCEPTION 'INVALID_COMMUNITY'; END IF;
   END LOOP;
   UPDATE nikon_community_settings SET settings=settings||(p_body->'settings') WHERE singleton RETURNING settings INTO cfg;
  END IF; result:=jsonb_build_object('settings',cfg);
 ELSE RAISE EXCEPTION 'INVALID_COMMUNITY';
 END IF;
 IF is_admin AND p_action NOT IN('adminSettings','adminRestrictions') THEN INSERT INTO nikon_community_audit(actor,action,target,detail) VALUES(coalesce(p_body->>'actor','admin'),p_action,coalesce(p_id::text,''),
  CASE WHEN p_action='adminSetSettings' THEN jsonb_build_object('before',previous_cfg,'after',cfg) ELSE p_body-'settings' END); END IF;
 IF request_key IS NOT NULL THEN INSERT INTO nikon_community_idempotency(user_id,action,key,request_hash,result) VALUES(p_user_id,action_key,request_key,md5(p_body::text),result); END IF;
 RETURN result;
END; $$;

DO $$ DECLARE tbl text; fn record;
BEGIN
 FOREACH tbl IN ARRAY ARRAY['nikon_community_settings','nikon_community_restrictions','nikon_community_assets','nikon_community_submissions','nikon_community_versions','nikon_community_activity','nikon_community_comments','nikon_community_reports','nikon_community_messages','nikon_community_idempotency','nikon_community_audit','nikon_community_risk_windows','nikon_abuse_windows','nikon_auth_challenges'] LOOP
  EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('REVOKE ALL ON public.%I FROM PUBLIC, anon, authenticated',tbl);
  EXECUTE format('GRANT ALL ON public.%I TO service_role',tbl);
 END LOOP;
 FOR fn IN SELECT p.oid::regprocedure signature FROM pg_proc p JOIN pg_namespace ns ON ns.oid=p.pronamespace WHERE ns.nspname='public' AND (p.proname LIKE 'nikon_community%' OR p.proname IN('nikon_abuse_tick','nikon_abuse_allowed','nikon_issue_challenge','nikon_consume_challenge')) LOOP
  EXECUTE format('REVOKE ALL ON FUNCTION %s FROM PUBLIC, anon, authenticated',fn.signature);
  EXECUTE format('GRANT EXECUTE ON FUNCTION %s TO service_role',fn.signature);
 END LOOP;
END $$;
GRANT USAGE,SELECT ON SEQUENCE public.nikon_community_activity_id_seq,public.nikon_community_audit_id_seq TO service_role;
NOTIFY pgrst,'reload schema';



