-- Password credentials belong only to ordinary App users, never to nikon_admins.
-- No public/client database role can read password hashes or mint a user session.
CREATE TABLE IF NOT EXISTS public.nikon_password_credentials (
  username text PRIMARY KEY CHECK (username ~ '^[a-z][a-z0-9_]{2,31}$'),
  user_id uuid NOT NULL UNIQUE REFERENCES public.nikon_users(id),
  password_hash text NOT NULL CHECK (password_hash ~ '^scrypt:32768:8:1:[a-f0-9]{32}:[a-f0-9]{128}$'),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE public.nikon_password_credentials ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.nikon_password_credentials FROM PUBLIC, anon, authenticated;
GRANT ALL ON public.nikon_password_credentials TO service_role;

CREATE OR REPLACE FUNCTION public.nikon_user_detail(p_id uuid)
RETURNS jsonb LANGUAGE plpgsql SECURITY INVOKER SET search_path = public, pg_temp AS $function$
DECLARE current_user_row nikon_users%ROWTYPE; masked_subject text; account_username text;
BEGIN
  SELECT * INTO current_user_row FROM nikon_users WHERE id = p_id;
  IF NOT FOUND THEN RAISE EXCEPTION 'USER_NOT_FOUND'; END IF;
  SELECT username INTO account_username FROM nikon_password_credentials WHERE user_id = p_id;
  SELECT left(provider_subject, 4) || '…' || right(provider_subject, 4) INTO masked_subject
    FROM nikon_user_identities WHERE user_id = p_id AND provider = 'qq' ORDER BY created_at, app_id LIMIT 1;
  RETURN jsonb_build_object('id', current_user_row.id, 'username', coalesce(account_username, ''),
    'nickname', current_user_row.nickname, 'avatarUrl', current_user_row.avatar_url,
    'provider', CASE WHEN account_username IS NOT NULL THEN 'password' ELSE 'qq' END, 'status', current_user_row.status,
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
  SELECT count(*) INTO matched_count FROM nikon_users u LEFT JOIN nikon_password_credentials c ON c.user_id = u.id
    WHERE (p_status = 'all' OR u.status = p_status)
      AND (p_q = '' OR position(lower(p_q) in lower(u.nickname)) > 0 OR position(lower(p_q) in u.id::text) > 0
        OR position(lower(p_q) in c.username) > 0);
  SELECT coalesce(jsonb_agg(item ORDER BY last_login_at DESC, id), '[]'::jsonb) INTO item_rows FROM (
    SELECT u.id, u.last_login_at, nikon_user_detail(u.id) - 'qqOpenIdMasked' AS item
      FROM nikon_users u LEFT JOIN nikon_password_credentials c ON c.user_id = u.id
      WHERE (p_status = 'all' OR u.status = p_status)
        AND (p_q = '' OR position(lower(p_q) in lower(u.nickname)) > 0 OR position(lower(p_q) in u.id::text) > 0
          OR position(lower(p_q) in c.username) > 0)
      ORDER BY u.last_login_at DESC, u.id LIMIT p_page_size OFFSET ((p_page::bigint - 1) * p_page_size)
  ) selected_users;
  SELECT count(*), count(*) FILTER (WHERE status = 'active'), count(*) FILTER (WHERE status = 'disabled')
    INTO all_count, active_count, disabled_count FROM nikon_users;
  RETURN jsonb_build_object('items', item_rows, 'total', matched_count, 'page', p_page, 'pageSize', p_page_size,
    'summary', jsonb_build_object('total', all_count, 'active', active_count, 'disabled', disabled_count));
END;
$function$;

-- Per-account and environment-wide counters are persisted across function instances.
-- They share the existing private attempt table, with disjoint namespaces from administrator/QQ login.
CREATE OR REPLACE FUNCTION public.nikon_password_auth_allowed(p_key text, p_registration boolean)
RETURNS boolean LANGUAGE plpgsql SECURITY INVOKER SET search_path = public, pg_temp AS $function$
DECLARE account_count integer; global_count integer; account_key text; global_key text;
BEGIN
  IF p_key IS NULL OR p_key !~ '^[a-f0-9]{64}$' OR p_registration IS NULL THEN
    RAISE EXCEPTION 'INVALID_PASSWORD_ACCOUNT';
  END IF;
  account_key := CASE WHEN p_registration THEN 'password-register:' ELSE 'password-login:' END || p_key;
  global_key := CASE WHEN p_registration THEN 'password-register-global' ELSE 'password-login-global' END;
  DELETE FROM nikon_login_attempts WHERE window_start < now() - interval '1 day';
  -- Always take the global row first so parallel calls lock in the same order.
  INSERT INTO nikon_login_attempts(attempt_key) VALUES(global_key)
    ON CONFLICT (attempt_key) DO UPDATE SET
      attempts = CASE WHEN nikon_login_attempts.window_start < now() - interval '15 minutes' THEN 1 ELSE nikon_login_attempts.attempts + 1 END,
      window_start = CASE WHEN nikon_login_attempts.window_start < now() - interval '15 minutes' THEN now() ELSE nikon_login_attempts.window_start END
    RETURNING attempts INTO global_count;
  INSERT INTO nikon_login_attempts(attempt_key) VALUES(account_key)
    ON CONFLICT (attempt_key) DO UPDATE SET
      attempts = CASE WHEN nikon_login_attempts.window_start < now() - interval '15 minutes' THEN 1 ELSE nikon_login_attempts.attempts + 1 END,
      window_start = CASE WHEN nikon_login_attempts.window_start < now() - interval '15 minutes' THEN now() ELSE nikon_login_attempts.window_start END
    RETURNING attempts INTO account_count;
  RETURN account_count <= 8 AND global_count <= CASE WHEN p_registration THEN 120 ELSE 600 END;
END;
$function$;

CREATE OR REPLACE FUNCTION public.nikon_register_password(p_username text, p_password_hash text,
  p_token_hash text, p_expires_at timestamptz)
RETURNS jsonb LANGUAGE plpgsql SECURITY INVOKER SET search_path = public, pg_temp AS $function$
DECLARE account_id uuid;
BEGIN
  IF p_username IS NULL OR p_username !~ '^[a-z][a-z0-9_]{2,31}$'
    OR p_password_hash IS NULL OR p_password_hash !~ '^scrypt:32768:8:1:[a-f0-9]{32}:[a-f0-9]{128}$'
    OR p_token_hash IS NULL OR p_token_hash !~ '^[a-f0-9]{64}$'
    OR p_expires_at IS NULL OR p_expires_at <= now() OR p_expires_at > now() + interval '8 days' THEN
    RAISE EXCEPTION 'INVALID_PASSWORD_ACCOUNT';
  END IF;
  -- The entire function is one transaction: an already used name never leaves an orphan user/session.
  PERFORM pg_advisory_xact_lock(hashtextextended('password:' || p_username, 0));
  IF EXISTS (SELECT 1 FROM nikon_password_credentials WHERE username = p_username) THEN
    RAISE EXCEPTION 'USERNAME_TAKEN';
  END IF;
  INSERT INTO nikon_users(nickname) VALUES(p_username) RETURNING id INTO account_id;
  INSERT INTO nikon_password_credentials(username, user_id, password_hash) VALUES(p_username, account_id, p_password_hash);
  INSERT INTO nikon_user_sessions(token_hash, user_id, expires_at) VALUES(p_token_hash, account_id, p_expires_at);
  DELETE FROM nikon_user_sessions WHERE expires_at <= now();
  RETURN nikon_user_detail(account_id);
END;
$function$;

-- Only the server may call this after a successful constant-work scrypt verification.
-- Rechecking the locked credential closes the verification/password-change race.
CREATE OR REPLACE FUNCTION public.nikon_sign_in_password(p_username text, p_verified_hash text,
  p_token_hash text, p_expires_at timestamptz)
RETURNS jsonb LANGUAGE plpgsql SECURITY INVOKER SET search_path = public, pg_temp AS $function$
DECLARE credential nikon_password_credentials%ROWTYPE; account nikon_users%ROWTYPE;
BEGIN
  IF p_username IS NULL OR p_username !~ '^[a-z][a-z0-9_]{2,31}$'
    OR p_verified_hash IS NULL OR p_verified_hash !~ '^scrypt:32768:8:1:[a-f0-9]{32}:[a-f0-9]{128}$'
    OR p_token_hash IS NULL OR p_token_hash !~ '^[a-f0-9]{64}$'
    OR p_expires_at IS NULL OR p_expires_at <= now() OR p_expires_at > now() + interval '8 days' THEN
    RAISE EXCEPTION 'INVALID_PASSWORD_ACCOUNT';
  END IF;
  SELECT * INTO credential FROM nikon_password_credentials WHERE username = p_username FOR UPDATE;
  IF NOT FOUND OR credential.password_hash IS DISTINCT FROM p_verified_hash THEN RAISE EXCEPTION 'USER_LOGIN_FAILED'; END IF;
  SELECT * INTO account FROM nikon_users WHERE id = credential.user_id FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'USER_LOGIN_FAILED'; END IF;
  IF account.status = 'disabled' THEN RAISE EXCEPTION 'USER_DISABLED'; END IF;
  UPDATE nikon_users SET login_count = login_count + 1, last_login_at = clock_timestamp(),
    updated_at = greatest(clock_timestamp(), updated_at + interval '1 microsecond') WHERE id = account.id;
  INSERT INTO nikon_user_sessions(token_hash, user_id, expires_at) VALUES(p_token_hash, account.id, p_expires_at);
  DELETE FROM nikon_user_sessions WHERE expires_at <= now();
  DELETE FROM nikon_user_sessions WHERE user_id = account.id AND token_hash IN (
    SELECT token_hash FROM nikon_user_sessions WHERE user_id = account.id AND token_hash <> p_token_hash
      ORDER BY created_at DESC, token_hash OFFSET 9
  );
  RETURN nikon_user_detail(account.id);
END;
$function$;

REVOKE ALL ON FUNCTION public.nikon_user_detail(uuid), public.nikon_users_page(text,text,integer,integer),
  public.nikon_password_auth_allowed(text,boolean), public.nikon_register_password(text,text,text,timestamptz),
  public.nikon_sign_in_password(text,text,text,timestamptz) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.nikon_user_detail(uuid), public.nikon_users_page(text,text,integer,integer),
  public.nikon_password_auth_allowed(text,boolean), public.nikon_register_password(text,text,text,timestamptz),
  public.nikon_sign_in_password(text,text,text,timestamptz) TO service_role;
NOTIFY pgrst, 'reload schema';
