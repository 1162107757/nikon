-- App users and QQ identities are separate from nikon_admins and admin sessions.
-- QQ access tokens are verified by the server and are never stored here.
CREATE TABLE IF NOT EXISTS public.nikon_users (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  nickname text NOT NULL CHECK (length(nickname) BETWEEN 1 AND 160),
  avatar_url text NOT NULL DEFAULT '' CHECK (length(avatar_url) <= 2048),
  status text NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'disabled')),
  created_at timestamptz NOT NULL DEFAULT now(),
  last_login_at timestamptz NOT NULL DEFAULT now(),
  login_count bigint NOT NULL DEFAULT 1 CHECK (login_count > 0),
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS nikon_users_last_login ON public.nikon_users(last_login_at DESC, id);
CREATE INDEX IF NOT EXISTS nikon_users_status ON public.nikon_users(status);
CREATE TABLE IF NOT EXISTS public.nikon_user_identities (
  provider text NOT NULL CHECK (provider = 'qq'),
  app_id text NOT NULL CHECK (app_id ~ '^[0-9]{5,32}$'),
  provider_subject text NOT NULL CHECK (provider_subject ~ '^[A-F0-9]{32}$'),
  user_id uuid NOT NULL REFERENCES public.nikon_users(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (provider, app_id, provider_subject),
  UNIQUE (provider, app_id, user_id)
);
CREATE INDEX IF NOT EXISTS nikon_user_identities_user ON public.nikon_user_identities(user_id);
CREATE TABLE IF NOT EXISTS public.nikon_user_sessions (
  token_hash text PRIMARY KEY CHECK (token_hash ~ '^[a-f0-9]{64}$'),
  user_id uuid NOT NULL REFERENCES public.nikon_users(id),
  expires_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS nikon_user_sessions_expiry ON public.nikon_user_sessions(expires_at);
CREATE INDEX IF NOT EXISTS nikon_user_sessions_user ON public.nikon_user_sessions(user_id, created_at DESC);

ALTER TABLE public.nikon_users ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nikon_user_identities ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nikon_user_sessions ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.nikon_users, public.nikon_user_identities, public.nikon_user_sessions FROM PUBLIC, anon, authenticated;
GRANT ALL ON public.nikon_users, public.nikon_user_identities, public.nikon_user_sessions TO service_role;

CREATE OR REPLACE FUNCTION public.nikon_user_detail(p_id uuid)
RETURNS jsonb LANGUAGE plpgsql SECURITY INVOKER SET search_path = public, pg_temp AS $function$
DECLARE current_user_row nikon_users%ROWTYPE; masked_subject text;
BEGIN
  SELECT * INTO current_user_row FROM nikon_users WHERE id = p_id;
  IF NOT FOUND THEN RAISE EXCEPTION 'USER_NOT_FOUND'; END IF;
  SELECT left(provider_subject, 4) || '…' || right(provider_subject, 4) INTO masked_subject
    FROM nikon_user_identities WHERE user_id = p_id AND provider = 'qq' ORDER BY created_at, app_id LIMIT 1;
  RETURN jsonb_build_object('id', current_user_row.id, 'nickname', current_user_row.nickname,
    'avatarUrl', current_user_row.avatar_url, 'provider', 'qq', 'status', current_user_row.status,
    'createdAt', current_user_row.created_at, 'lastLoginAt', current_user_row.last_login_at,
    'loginCount', current_user_row.login_count, 'updatedAt', current_user_row.updated_at,
    'qqOpenIdMasked', coalesce(masked_subject, ''));
END;
$function$;

CREATE OR REPLACE FUNCTION public.nikon_users_page(p_q text, p_status text, p_page integer, p_page_size integer)
RETURNS jsonb LANGUAGE plpgsql SECURITY INVOKER SET search_path = public, pg_temp AS $function$
DECLARE item_rows jsonb; matched_count bigint; all_count bigint; active_count bigint; disabled_count bigint;
BEGIN
  IF p_q IS NULL OR length(p_q) > 80 OR p_status IS NULL OR p_status NOT IN ('all', 'active', 'disabled')
    OR p_page IS NULL OR p_page NOT BETWEEN 1 AND 100000 OR p_page_size IS NULL OR p_page_size NOT BETWEEN 1 AND 100 THEN
    RAISE EXCEPTION 'INVALID_USER_QUERY';
  END IF;
  -- Literal substring search: user-supplied %, _, commas, and parentheses are never query operators.
  SELECT count(*) INTO matched_count FROM nikon_users WHERE (p_status = 'all' OR status = p_status)
    AND (p_q = '' OR position(lower(p_q) in lower(nickname)) > 0 OR position(lower(p_q) in id::text) > 0);
  SELECT coalesce(jsonb_agg(item ORDER BY last_login_at DESC, id), '[]'::jsonb) INTO item_rows FROM (
    SELECT id, last_login_at, jsonb_build_object('id', id, 'nickname', nickname, 'avatarUrl', avatar_url,
      'provider', 'qq', 'status', status, 'createdAt', created_at, 'lastLoginAt', last_login_at,
      'loginCount', login_count, 'updatedAt', updated_at) AS item
    FROM nikon_users WHERE (p_status = 'all' OR status = p_status)
      AND (p_q = '' OR position(lower(p_q) in lower(nickname)) > 0 OR position(lower(p_q) in id::text) > 0)
    ORDER BY last_login_at DESC, id LIMIT p_page_size OFFSET ((p_page::bigint - 1) * p_page_size)
  ) selected_users;
  SELECT count(*), count(*) FILTER (WHERE status = 'active'), count(*) FILTER (WHERE status = 'disabled')
    INTO all_count, active_count, disabled_count FROM nikon_users;
  RETURN jsonb_build_object('items', item_rows, 'total', matched_count, 'page', p_page, 'pageSize', p_page_size,
    'summary', jsonb_build_object('total', all_count, 'active', active_count, 'disabled', disabled_count));
END;
$function$;

CREATE OR REPLACE FUNCTION public.nikon_set_user_status(p_id uuid, p_status text, p_expected_updated_at timestamptz)
RETURNS jsonb LANGUAGE plpgsql SECURITY INVOKER SET search_path = public, pg_temp AS $function$
DECLARE current_user_row nikon_users%ROWTYPE;
BEGIN
  IF p_status IS NULL OR p_status NOT IN ('active', 'disabled') THEN RAISE EXCEPTION 'INVALID_USER_STATUS'; END IF;
  SELECT * INTO current_user_row FROM nikon_users WHERE id = p_id FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'USER_NOT_FOUND'; END IF;
  IF p_expected_updated_at IS NULL OR current_user_row.updated_at IS DISTINCT FROM p_expected_updated_at THEN
    RAISE EXCEPTION 'USER_CONFLICT';
  END IF;
  IF p_status <> current_user_row.status THEN
    UPDATE nikon_users SET status = p_status,
      updated_at = greatest(clock_timestamp(), current_user_row.updated_at + interval '1 microsecond') WHERE id = p_id;
  END IF;
  -- Disabling is atomic with revocation; enabling never restores an old token.
  IF p_status = 'disabled' THEN DELETE FROM nikon_user_sessions WHERE user_id = p_id; END IF;
  RETURN nikon_user_detail(p_id);
END;
$function$;

-- Caller must verify QQ's access token, client_id and returned openid before invoking this server-only RPC.
CREATE OR REPLACE FUNCTION public.nikon_sign_in_qq(p_app_id text, p_openid text, p_nickname text,
  p_avatar_url text, p_token_hash text, p_expires_at timestamptz)
RETURNS jsonb LANGUAGE plpgsql SECURITY INVOKER SET search_path = public, pg_temp AS $function$
DECLARE current_user_row nikon_users%ROWTYPE; identity_user_id uuid; subject text := upper(p_openid);
BEGIN
  IF p_app_id IS NULL OR p_app_id !~ '^[0-9]{5,32}$' OR subject IS NULL OR subject !~ '^[A-F0-9]{32}$'
    OR p_nickname IS NULL OR length(p_nickname) NOT BETWEEN 1 AND 160 OR p_nickname ~ '[[:cntrl:]]'
    OR p_avatar_url IS NULL OR length(p_avatar_url) > 2048 OR (p_avatar_url <> '' AND p_avatar_url !~ '^https://[^[:space:]]+$')
    OR p_token_hash IS NULL OR p_token_hash !~ '^[a-f0-9]{64}$' OR p_expires_at IS NULL
    OR p_expires_at <= now() OR p_expires_at > now() + interval '8 days' THEN
    RAISE EXCEPTION 'INVALID_QQ_IDENTITY';
  END IF;
  -- One identity gets one user, even when two phones complete a first login concurrently.
  PERFORM pg_advisory_xact_lock(hashtextextended('qq:' || p_app_id || ':' || subject, 0));
  SELECT user_id INTO identity_user_id FROM nikon_user_identities
    WHERE provider = 'qq' AND app_id = p_app_id AND provider_subject = subject;
  IF identity_user_id IS NULL THEN
    INSERT INTO nikon_users(nickname, avatar_url) VALUES(p_nickname, p_avatar_url) RETURNING * INTO current_user_row;
    INSERT INTO nikon_user_identities(provider, app_id, provider_subject, user_id)
      VALUES('qq', p_app_id, subject, current_user_row.id);
  ELSE
    SELECT * INTO current_user_row FROM nikon_users WHERE id = identity_user_id FOR UPDATE;
    IF current_user_row.status = 'disabled' THEN RAISE EXCEPTION 'USER_DISABLED'; END IF;
    UPDATE nikon_users SET nickname = p_nickname, avatar_url = p_avatar_url, login_count = login_count + 1,
      last_login_at = clock_timestamp(), updated_at = greatest(clock_timestamp(), updated_at + interval '1 microsecond')
      WHERE id = identity_user_id RETURNING * INTO current_user_row;
    UPDATE nikon_user_identities SET updated_at = clock_timestamp()
      WHERE provider = 'qq' AND app_id = p_app_id AND provider_subject = subject;
  END IF;
  INSERT INTO nikon_user_sessions(token_hash, user_id, expires_at) VALUES(p_token_hash, current_user_row.id, p_expires_at);
  DELETE FROM nikon_user_sessions WHERE expires_at <= now();
  DELETE FROM nikon_user_sessions WHERE user_id = current_user_row.id AND token_hash IN (
    SELECT token_hash FROM nikon_user_sessions WHERE user_id = current_user_row.id AND token_hash <> p_token_hash
      ORDER BY created_at DESC, token_hash OFFSET 9
  );
  RETURN nikon_user_detail(current_user_row.id);
END;
$function$;

CREATE OR REPLACE FUNCTION public.nikon_user_session(p_token_hash text)
RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path = public, pg_temp AS $function$
  SELECT jsonb_build_object('user', nikon_user_detail(u.id) - 'qqOpenIdMasked', 'expiresAt', s.expires_at)
    FROM nikon_user_sessions s JOIN nikon_users u ON u.id = s.user_id
    WHERE s.token_hash = p_token_hash AND s.expires_at > now() AND u.status = 'active' LIMIT 1;
$function$;

REVOKE ALL ON FUNCTION public.nikon_user_detail(uuid), public.nikon_users_page(text,text,integer,integer),
  public.nikon_set_user_status(uuid,text,timestamptz), public.nikon_sign_in_qq(text,text,text,text,text,timestamptz),
  public.nikon_user_session(text) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.nikon_user_detail(uuid), public.nikon_users_page(text,text,integer,integer),
  public.nikon_set_user_status(uuid,text,timestamptz), public.nikon_sign_in_qq(text,text,text,text,text,timestamptz),
  public.nikon_user_session(text) TO service_role;
NOTIFY pgrst, 'reload schema';
