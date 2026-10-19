-- Avatars stay in the existing PRIVATE storage bucket. Only /avatars/<owned UUID>.jpg
-- can read a committed current avatar; this does not change preset/publication permissions.
CREATE TABLE IF NOT EXISTS public.nikon_avatar_uploads (
 id uuid PRIMARY KEY, user_id uuid NOT NULL REFERENCES public.nikon_users(id),
 expected_updated_at timestamptz NOT NULL, created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 payload jsonb, committed_at timestamptz
);
CREATE INDEX IF NOT EXISTS nikon_avatar_uploads_user ON public.nikon_avatar_uploads(user_id,created_at DESC);
CREATE TABLE IF NOT EXISTS public.nikon_avatar_storage_budget (
 id boolean PRIMARY KEY DEFAULT true CHECK(id), reserved_bytes bigint NOT NULL DEFAULT 0 CHECK(reserved_bytes>=0)
);
INSERT INTO public.nikon_avatar_storage_budget(id) VALUES(true) ON CONFLICT DO NOTHING;
ALTER TABLE public.nikon_avatar_uploads ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nikon_avatar_storage_budget ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.nikon_avatar_uploads,public.nikon_avatar_storage_budget FROM PUBLIC,anon,authenticated;
GRANT ALL ON public.nikon_avatar_uploads,public.nikon_avatar_storage_budget TO service_role;

CREATE OR REPLACE FUNCTION public.nikon_valid_qq_avatar(p_url text)
RETURNS boolean LANGUAGE sql IMMUTABLE SECURITY INVOKER SET search_path=public,pg_temp AS $$
 SELECT p_url IS NOT NULL AND length(p_url)<=2048 AND
 (p_url='' OR (p_url ~ '^https://([A-Za-z0-9-]+\.)*qlogo\.cn/[^[:space:]#]*$'
 AND p_url !~* '[?&](access_token|token|openid)='));
$$;
ALTER TABLE public.nikon_users ADD COLUMN IF NOT EXISTS avatar_source text NOT NULL DEFAULT 'default';
ALTER TABLE public.nikon_users ADD COLUMN IF NOT EXISTS qq_avatar_url text NOT NULL DEFAULT '';
ALTER TABLE public.nikon_users ADD COLUMN IF NOT EXISTS avatar_asset_id uuid REFERENCES public.nikon_avatar_uploads(id);
-- Existing QQ accounts have provider avatars, never a user-selected custom avatar yet.
-- A rerun must preserve both a custom avatar and the newer provider snapshot.
UPDATE public.nikon_users u SET qq_avatar_url=avatar_url,avatar_source='qq'
 WHERE avatar_source='default' AND avatar_asset_id IS NULL AND qq_avatar_url='' AND avatar_url<>''
 AND nikon_valid_qq_avatar(avatar_url)
 AND EXISTS(SELECT 1 FROM public.nikon_user_identities i WHERE i.user_id=u.id AND i.provider='qq');
DO $$ BEGIN
 IF NOT EXISTS(SELECT 1 FROM pg_constraint WHERE conrelid='public.nikon_users'::regclass AND conname='nikon_users_avatar_source_check') THEN
 ALTER TABLE public.nikon_users ADD CONSTRAINT nikon_users_avatar_source_check CHECK(avatar_source IN('default','qq','custom'));
 END IF;
END; $$;

CREATE OR REPLACE FUNCTION public.nikon_user_detail(p_id uuid)
RETURNS jsonb LANGUAGE plpgsql SECURITY INVOKER SET search_path=public,pg_temp AS $$
DECLARE u nikon_users%ROWTYPE; masked_subject text; account_username text;
BEGIN
 SELECT * INTO u FROM nikon_users WHERE id=p_id;
 IF NOT FOUND THEN RAISE EXCEPTION 'USER_NOT_FOUND'; END IF;
 SELECT username INTO account_username FROM nikon_password_credentials WHERE user_id=p_id;
 SELECT left(provider_subject,4)||'…'||right(provider_subject,4) INTO masked_subject
 FROM nikon_user_identities WHERE user_id=p_id AND provider='qq' ORDER BY created_at,app_id LIMIT 1;
 RETURN jsonb_build_object('id',u.id,'username',coalesce(account_username,''),'nickname',u.nickname,
 'avatarUrl',u.avatar_url,'avatarSource',u.avatar_source,
 'provider',CASE WHEN account_username IS NOT NULL THEN 'password' ELSE 'qq' END,'status',u.status,
 'createdAt',u.created_at,'lastLoginAt',u.last_login_at,'loginCount',u.login_count,'updatedAt',u.updated_at,
 'qqOpenIdMasked',coalesce(masked_subject,''));
END; $$;

-- A separate transaction reserves the full maximum bytes before storage is touched. No
-- refunds: malformed/truncated/CAS-conflicted transfers also consume durable budgets.
-- Daily global/source/install/account limits plus an 8 GiB lifetime reserved-byte ceiling
-- bound orphan objects as well as accepted images across server restarts and instances.
CREATE OR REPLACE FUNCTION public.nikon_avatar_reserve(p_user_id uuid,p_id uuid,p_expected_updated_at timestamptz,
 p_source_hash text,p_installation_hash text)
RETURNS jsonb LANGUAGE plpgsql SECURITY INVOKER SET search_path=public,pg_temp AS $$
DECLARE u nikon_users%ROWTYPE; a boolean; b boolean; c boolean; d boolean; budget bigint;
BEGIN
 IF p_id IS NULL OR p_expected_updated_at IS NULL OR p_source_hash IS NULL OR p_source_hash !~ '^[a-f0-9]{64}$'
 OR p_installation_hash IS NULL OR p_installation_hash !~ '^[a-f0-9]{64}$' THEN RAISE EXCEPTION 'INVALID_AVATAR'; END IF;
 SELECT * INTO u FROM nikon_users WHERE id=p_user_id FOR UPDATE;
 IF NOT FOUND OR u.status<>'active' THEN RAISE EXCEPTION 'USER_LOGIN_REQUIRED'; END IF;
 IF u.updated_at IS DISTINCT FROM p_expected_updated_at THEN RAISE EXCEPTION 'USER_CONFLICT'; END IF;
 -- Lock order is always global, source, installation, account, storage budget.
 a:=nikon_abuse_tick('avatar:global',512,86400);
 b:=nikon_abuse_tick('avatar:source:'||p_source_hash,120,86400);
 c:=nikon_abuse_tick('avatar:installation:'||p_installation_hash,60,86400);
 d:=nikon_abuse_tick('avatar:account:'||p_user_id::text,12,86400);
 IF NOT(a AND b AND c AND d) THEN RETURN jsonb_build_object('allowed',false); END IF;
 UPDATE nikon_avatar_storage_budget SET reserved_bytes=reserved_bytes+1048576
 WHERE id AND reserved_bytes<=8589934592-1048576 RETURNING reserved_bytes INTO budget;
 IF budget IS NULL THEN RETURN jsonb_build_object('allowed',false); END IF;
 INSERT INTO nikon_avatar_uploads(id,user_id,expected_updated_at) VALUES(p_id,p_user_id,p_expected_updated_at);
 RETURN jsonb_build_object('allowed',true);
END; $$;

CREATE OR REPLACE FUNCTION public.nikon_avatar_set(p_user_id uuid,p_id uuid,p_expected_updated_at timestamptz,p_avatar jsonb)
RETURNS jsonb LANGUAGE plpgsql SECURITY INVOKER SET search_path=public,pg_temp AS $$
DECLARE u nikon_users%ROWTYPE; upload nikon_avatar_uploads%ROWTYPE; image_width integer; image_height integer; n integer; address text;
BEGIN
 SELECT * INTO u FROM nikon_users WHERE id=p_user_id FOR UPDATE;
 IF NOT FOUND OR u.status<>'active' THEN RAISE EXCEPTION 'USER_LOGIN_REQUIRED'; END IF;
 IF p_expected_updated_at IS NULL OR u.updated_at IS DISTINCT FROM p_expected_updated_at THEN RAISE EXCEPTION 'USER_CONFLICT'; END IF;
 SELECT * INTO upload FROM nikon_avatar_uploads WHERE id=p_id AND user_id=p_user_id FOR UPDATE;
 IF NOT FOUND OR upload.committed_at IS NOT NULL OR upload.expected_updated_at IS DISTINCT FROM p_expected_updated_at
 OR upload.created_at<clock_timestamp()-interval '10 minutes' THEN RAISE EXCEPTION 'AVATAR_RESERVATION_EXPIRED'; END IF;
 IF p_avatar IS NULL OR jsonb_typeof(p_avatar)<>'object'
 OR jsonb_typeof(p_avatar->'byteLength') IS DISTINCT FROM 'number' OR (p_avatar->>'byteLength') !~ '^[1-9][0-9]{0,6}$'
 OR jsonb_typeof(p_avatar->'width') IS DISTINCT FROM 'number' OR (p_avatar->>'width') !~ '^[1-9][0-9]{1,3}$'
 OR jsonb_typeof(p_avatar->'height') IS DISTINCT FROM 'number' OR (p_avatar->>'height') !~ '^[1-9][0-9]{1,3}$'
 OR p_avatar->>'mimeType' IS DISTINCT FROM 'image/jpeg' OR p_avatar->>'extension' IS DISTINCT FROM 'jpg'
 OR coalesce(p_avatar->>'sha256','') !~ '^[a-f0-9]{64}$'
 OR p_avatar->>'storagePath' IS DISTINCT FROM 'avatars/'||p_id::text||'.jpg'
 THEN RAISE EXCEPTION 'INVALID_AVATAR'; END IF;
 n:=(p_avatar->>'byteLength')::integer; image_width:=(p_avatar->>'width')::integer; image_height:=(p_avatar->>'height')::integer;
 address:=p_avatar->>'url';
 IF n NOT BETWEEN 1 AND 1048576 OR image_width<>image_height OR image_width NOT BETWEEN 64 AND 1024
 OR address IS NULL OR length(address)>2048 OR address !~ '^https?://[^[:space:]?#@]+/avatars/[a-f0-9-]+\.jpg$'
 OR right(address,length('/avatars/'||p_id::text||'.jpg'))<>'/avatars/'||p_id::text||'.jpg'
 THEN RAISE EXCEPTION 'INVALID_AVATAR'; END IF;
 UPDATE nikon_avatar_uploads SET payload=jsonb_build_object('id',p_id,'sha256',p_avatar->>'sha256','byteLength',n,
 'mimeType','image/jpeg','storagePath','avatars/'||p_id::text||'.jpg'),committed_at=clock_timestamp() WHERE id=p_id;
 UPDATE nikon_users SET avatar_source='custom',avatar_url=address,avatar_asset_id=p_id,
 updated_at=greatest(clock_timestamp(),u.updated_at+interval '1 microsecond') WHERE id=p_user_id;
 RETURN nikon_user_detail(p_user_id)-'qqOpenIdMasked';
END; $$;

CREATE OR REPLACE FUNCTION public.nikon_avatar_reset(p_user_id uuid,p_expected_updated_at timestamptz)
RETURNS jsonb LANGUAGE plpgsql SECURITY INVOKER SET search_path=public,pg_temp AS $$
DECLARE u nikon_users%ROWTYPE; provider_avatar text;
BEGIN
 SELECT * INTO u FROM nikon_users WHERE id=p_user_id FOR UPDATE;
 IF NOT FOUND OR u.status<>'active' THEN RAISE EXCEPTION 'USER_LOGIN_REQUIRED'; END IF;
 IF p_expected_updated_at IS NULL OR u.updated_at IS DISTINCT FROM p_expected_updated_at THEN RAISE EXCEPTION 'USER_CONFLICT'; END IF;
 provider_avatar:=CASE WHEN nikon_valid_qq_avatar(u.qq_avatar_url) AND EXISTS(SELECT 1 FROM nikon_user_identities WHERE user_id=p_user_id AND provider='qq') THEN u.qq_avatar_url ELSE '' END;
 UPDATE nikon_users SET avatar_url=provider_avatar,avatar_source=CASE WHEN provider_avatar<>'' THEN 'qq' ELSE 'default' END,
 avatar_asset_id=NULL,updated_at=greatest(clock_timestamp(),u.updated_at+interval '1 microsecond') WHERE id=p_user_id;
 RETURN nikon_user_detail(p_user_id)-'qqOpenIdMasked';
END; $$;

CREATE OR REPLACE FUNCTION public.nikon_avatar_asset(p_id uuid)
RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path=public,pg_temp AS $$
 SELECT a.payload FROM nikon_avatar_uploads a JOIN nikon_users u ON u.avatar_asset_id=a.id
 WHERE a.id=p_id AND a.committed_at IS NOT NULL AND u.status='active' AND u.avatar_source='custom' LIMIT 1;
$$;

-- QQ is the default source, but later QQ logins may only refresh the saved provider
-- snapshot while a custom avatar is selected. Reset then uses the newest snapshot.
CREATE OR REPLACE FUNCTION public.nikon_sign_in_qq(p_app_id text,p_openid text,p_nickname text,
 p_avatar_url text,p_token_hash text,p_expires_at timestamptz)
RETURNS jsonb LANGUAGE plpgsql SECURITY INVOKER SET search_path=public,pg_temp AS $$
DECLARE u nikon_users%ROWTYPE; identity_user_id uuid; subject text:=upper(p_openid);
BEGIN
 IF p_app_id IS NULL OR p_app_id !~ '^[0-9]{5,32}$' OR subject IS NULL OR subject !~ '^[A-F0-9]{32}$'
 OR p_nickname IS NULL OR length(p_nickname) NOT BETWEEN 1 AND 160 OR p_nickname ~ '[[:cntrl:]]'
 OR NOT nikon_valid_qq_avatar(p_avatar_url) OR p_token_hash IS NULL OR p_token_hash !~ '^[a-f0-9]{64}$'
 OR p_expires_at IS NULL OR p_expires_at<=now() OR p_expires_at>now()+interval '8 days' THEN RAISE EXCEPTION 'INVALID_QQ_IDENTITY'; END IF;
 PERFORM pg_advisory_xact_lock(hashtextextended('qq:'||p_app_id||':'||subject,0));
 SELECT user_id INTO identity_user_id FROM nikon_user_identities WHERE provider='qq' AND app_id=p_app_id AND provider_subject=subject;
 IF identity_user_id IS NULL THEN
 INSERT INTO nikon_users(nickname,avatar_url,qq_avatar_url,avatar_source)
 VALUES(p_nickname,p_avatar_url,p_avatar_url,CASE WHEN p_avatar_url<>'' THEN 'qq' ELSE 'default' END) RETURNING * INTO u;
 INSERT INTO nikon_user_identities(provider,app_id,provider_subject,user_id) VALUES('qq',p_app_id,subject,u.id);
 ELSE
 SELECT * INTO u FROM nikon_users WHERE id=identity_user_id FOR UPDATE;
 IF u.status='disabled' THEN RAISE EXCEPTION 'USER_DISABLED'; END IF;
 UPDATE nikon_users SET nickname=p_nickname,qq_avatar_url=CASE WHEN p_avatar_url<>'' THEN p_avatar_url ELSE qq_avatar_url END,
 avatar_url=CASE WHEN avatar_source='custom' THEN avatar_url WHEN p_avatar_url<>'' THEN p_avatar_url ELSE qq_avatar_url END,
 avatar_source=CASE WHEN avatar_source='custom' THEN 'custom' WHEN p_avatar_url<>'' OR qq_avatar_url<>'' THEN 'qq' ELSE 'default' END,
 login_count=login_count+1,last_login_at=clock_timestamp(),updated_at=greatest(clock_timestamp(),updated_at+interval '1 microsecond')
 WHERE id=identity_user_id RETURNING * INTO u;
 UPDATE nikon_user_identities SET updated_at=clock_timestamp() WHERE provider='qq' AND app_id=p_app_id AND provider_subject=subject;
 END IF;
 INSERT INTO nikon_user_sessions(token_hash,user_id,expires_at) VALUES(p_token_hash,u.id,p_expires_at);
 DELETE FROM nikon_user_sessions WHERE expires_at<=now();
 DELETE FROM nikon_user_sessions WHERE user_id=u.id AND token_hash IN(SELECT token_hash FROM nikon_user_sessions
 WHERE user_id=u.id AND token_hash<>p_token_hash ORDER BY created_at DESC,token_hash OFFSET 9);
 RETURN nikon_user_detail(u.id);
END; $$;

DO $$ DECLARE fn record; BEGIN
 FOR fn IN SELECT p.oid::regprocedure signature FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
 WHERE n.nspname='public' AND (p.proname LIKE 'nikon_avatar_%' OR p.proname IN('nikon_valid_qq_avatar','nikon_user_detail','nikon_sign_in_qq')) LOOP
 EXECUTE format('REVOKE ALL ON FUNCTION %s FROM PUBLIC,anon,authenticated',fn.signature);
 EXECUTE format('GRANT EXECUTE ON FUNCTION %s TO service_role',fn.signature);
 END LOOP;
END; $$;
NOTIFY pgrst,'reload schema';
