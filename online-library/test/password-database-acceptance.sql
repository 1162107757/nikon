-- Real PostgreSQL acceptance: 28 assertions. Every fixture and RPC effect is rolled back.
-- Credentials use syntactically valid synthetic hashes; actual scrypt/password verification is covered by Node tests.
DO $nikon_password_acceptance$
DECLARE
  nonce text := replace(gen_random_uuid()::text, '-', '');
  account_name text; missing_name text; account_id uuid;
  encoded_hash text; next_hash text; first_token text; second_token text; latest_token text; expired_token text;
  fixture_attempt_key text; item jsonb; initial_user jsonb; disabled_user jsonb; page_result jsonb;
  before_users bigint; before_sessions bigint; before_count bigint; expected_error boolean;
  rollback_seen boolean := false; checks integer := 0; idx integer;
BEGIN
  account_name := 'pa_' || left(nonce, 24); missing_name := 'pm_' || left(nonce, 24);
  encoded_hash := 'scrypt:32768:8:1:' || nonce || ':' || repeat('a', 128);
  next_hash := 'scrypt:32768:8:1:' || nonce || ':' || repeat('b', 128);
  first_token := nonce || replace(gen_random_uuid()::text, '-', '');
  second_token := nonce || replace(gen_random_uuid()::text, '-', '');
  expired_token := nonce || replace(gen_random_uuid()::text, '-', '');
  fixture_attempt_key := nonce || replace(gen_random_uuid()::text, '-', '');
  BEGIN
    IF EXISTS (SELECT 1 FROM public.nikon_password_credentials WHERE username IN (account_name, missing_name))
      OR EXISTS (SELECT 1 FROM public.nikon_user_sessions WHERE token_hash IN (first_token, second_token, expired_token)) THEN
      RAISE EXCEPTION 'ASSERT 01: synthetic fixture collision';
    END IF;
    checks := checks + 1;
    item := public.nikon_register_password(account_name, encoded_hash, first_token, now() + interval '7 days');
    initial_user := item; account_id := (item->>'id')::uuid;
    IF account_id IS NULL OR item->>'username' IS DISTINCT FROM account_name OR item->>'provider' IS DISTINCT FROM 'password'
      OR item->>'nickname' IS DISTINCT FROM account_name OR item->>'status' IS DISTINCT FROM 'active'
      OR (item->>'loginCount')::integer <> 1 THEN
      RAISE EXCEPTION 'ASSERT 02: registration must create one active password profile';
    END IF;
    checks := checks + 1;
    IF (SELECT count(*) FROM public.nikon_password_credentials WHERE user_id = account_id) <> 1
      OR NOT EXISTS (SELECT 1 FROM public.nikon_password_credentials WHERE username = account_name AND password_hash = encoded_hash)
      OR EXISTS (SELECT 1 FROM public.nikon_user_identities WHERE user_id = account_id) THEN
      RAISE EXCEPTION 'ASSERT 03: password credential must be private and separate from QQ identity';
    END IF;
    checks := checks + 1;
    IF public.nikon_user_session(first_token)->'user'->>'id' IS DISTINCT FROM account_id::text
      OR public.nikon_user_session(first_token)->'user' ?| ARRAY['password_hash','password','token_hash','qqOpenIdMasked'] THEN
      RAISE EXCEPTION 'ASSERT 04: registration session must expose only a safe ordinary user profile';
    END IF;
    checks := checks + 1;
    SELECT count(*) INTO before_users FROM public.nikon_users;
    SELECT count(*) INTO before_sessions FROM public.nikon_user_sessions;
    expected_error := false;
    BEGIN
      PERFORM public.nikon_register_password(account_name, next_hash, second_token, now() + interval '7 days');
    EXCEPTION WHEN raise_exception THEN
      IF SQLERRM <> 'USERNAME_TAKEN' THEN RAISE; END IF; expected_error := true;
    END;
    IF NOT expected_error OR (SELECT count(*) FROM public.nikon_users) <> before_users
      OR (SELECT count(*) FROM public.nikon_user_sessions) <> before_sessions THEN
      RAISE EXCEPTION 'ASSERT 05: duplicate registration must leave no orphan user or session';
    END IF;
    checks := checks + 1;
    expected_error := false;
    BEGIN
      PERFORM public.nikon_register_password(upper(account_name), encoded_hash, second_token, now() + interval '7 days');
    EXCEPTION WHEN raise_exception THEN
      IF SQLERRM <> 'INVALID_PASSWORD_ACCOUNT' THEN RAISE; END IF; expected_error := true;
    END;
    IF NOT expected_error OR (SELECT count(*) FROM public.nikon_users) <> before_users THEN
      RAISE EXCEPTION 'ASSERT 06: database names must already be lowercase and normalized';
    END IF;
    checks := checks + 1;
    expected_error := false;
    BEGIN
      PERFORM public.nikon_register_password(missing_name, 'plaintext-is-never-valid', second_token, now() + interval '7 days');
    EXCEPTION WHEN raise_exception THEN
      IF SQLERRM <> 'INVALID_PASSWORD_ACCOUNT' THEN RAISE; END IF; expected_error := true;
    END;
    IF NOT expected_error OR (SELECT count(*) FROM public.nikon_users) <> before_users THEN
      RAISE EXCEPTION 'ASSERT 07: password field must accept only the expected scrypt encoding';
    END IF;
    checks := checks + 1;
    expected_error := false;
    BEGIN
      PERFORM public.nikon_sign_in_password(account_name, next_hash, second_token, now() + interval '7 days');
    EXCEPTION WHEN raise_exception THEN
      IF SQLERRM <> 'USER_LOGIN_FAILED' THEN RAISE; END IF; expected_error := true;
    END;
    IF NOT expected_error OR (SELECT login_count FROM public.nikon_users WHERE id = account_id) <> 1 THEN
      RAISE EXCEPTION 'ASSERT 08: stale or wrong verified hash cannot mint a session or increment logins';
    END IF;
    checks := checks + 1;
    expected_error := false;
    BEGIN
      PERFORM public.nikon_sign_in_password(missing_name, encoded_hash, second_token, now() + interval '7 days');
    EXCEPTION WHEN raise_exception THEN
      IF SQLERRM <> 'USER_LOGIN_FAILED' THEN RAISE; END IF; expected_error := true;
    END;
    IF NOT expected_error THEN RAISE EXCEPTION 'ASSERT 09: missing account uses the same login failure'; END IF;
    checks := checks + 1;
    item := public.nikon_sign_in_password(account_name, encoded_hash, second_token, now() + interval '7 days');
    IF item->>'id' IS DISTINCT FROM account_id::text OR (item->>'loginCount')::integer <> 2
      OR (item->>'updatedAt')::timestamptz <= (initial_user->>'updatedAt')::timestamptz
      OR public.nikon_user_session(second_token) IS NULL THEN
      RAISE EXCEPTION 'ASSERT 10: login must reuse user, advance login metadata and issue a new token';
    END IF;
    checks := checks + 1;
    UPDATE public.nikon_users SET nickname = '__password_synthetic_profile' WHERE id = account_id;
    page_result := public.nikon_users_page(upper(account_name), 'all', 1, 20);
    IF (page_result->>'total')::integer <> 1 OR page_result->'items'->0->>'username' IS DISTINCT FROM account_name
      OR page_result->'items'->0->>'provider' IS DISTINCT FROM 'password' THEN
      RAISE EXCEPTION 'ASSERT 11: admin search must match case-insensitive usernames independently of nickname';
    END IF;
    checks := checks + 1;
    IF public.nikon_user_detail(account_id) ?| ARRAY['password_hash','password','token_hash','accessToken']
      OR page_result->'items'->0 ?| ARRAY['password_hash','password','token_hash','accessToken','qqOpenIdMasked']
      OR position(encoded_hash in page_result::text) > 0 THEN
      RAISE EXCEPTION 'ASSERT 12: admin detail and page must never reveal credentials';
    END IF;
    checks := checks + 1;
    disabled_user := public.nikon_set_user_status(account_id, 'disabled', (item->>'updatedAt')::timestamptz);
    IF disabled_user->>'status' IS DISTINCT FROM 'disabled'
      OR EXISTS (SELECT 1 FROM public.nikon_user_sessions WHERE user_id = account_id)
      OR public.nikon_user_session(first_token) IS NOT NULL THEN
      RAISE EXCEPTION 'ASSERT 13: disable must atomically revoke every ordinary user token';
    END IF;
    checks := checks + 1;
    expected_error := false;
    BEGIN
      PERFORM public.nikon_sign_in_password(account_name, encoded_hash, second_token, now() + interval '7 days');
    EXCEPTION WHEN raise_exception THEN
      IF SQLERRM <> 'USER_DISABLED' THEN RAISE; END IF; expected_error := true;
    END;
    IF NOT expected_error OR EXISTS (SELECT 1 FROM public.nikon_user_sessions WHERE user_id = account_id) THEN
      RAISE EXCEPTION 'ASSERT 14: correct verified credentials must still reject a disabled user';
    END IF;
    checks := checks + 1;
    PERFORM public.nikon_set_user_status(account_id, 'active', (disabled_user->>'updatedAt')::timestamptz);
    IF public.nikon_user_session(first_token) IS NOT NULL OR public.nikon_user_session(second_token) IS NOT NULL THEN
      RAISE EXCEPTION 'ASSERT 15: enable must never revive revoked bearer tokens';
    END IF;
    checks := checks + 1;
    latest_token := nonce || replace(gen_random_uuid()::text, '-', '');
    item := public.nikon_sign_in_password(account_name, encoded_hash, latest_token, now() + interval '7 days');
    IF (item->>'loginCount')::integer <> 3 OR public.nikon_user_session(latest_token) IS NULL THEN
      RAISE EXCEPTION 'ASSERT 16: enabled account can log in with a fresh session';
    END IF;
    checks := checks + 1;
    FOR idx IN 1..11 LOOP
      latest_token := nonce || replace(gen_random_uuid()::text, '-', '');
      PERFORM public.nikon_sign_in_password(account_name, encoded_hash, latest_token, now() + interval '7 days');
    END LOOP;
    IF (SELECT count(*) FROM public.nikon_user_sessions WHERE user_id = account_id) <> 10
      OR public.nikon_user_session(latest_token) IS NULL THEN
      RAISE EXCEPTION 'ASSERT 17: per-user session retention is capped at ten and retains the latest';
    END IF;
    checks := checks + 1;
    INSERT INTO public.nikon_user_sessions(token_hash,user_id,expires_at) VALUES(expired_token,account_id,now() - interval '1 second');
    IF public.nikon_user_session(expired_token) IS NOT NULL THEN RAISE EXCEPTION 'ASSERT 18: expired token is unusable'; END IF;
    checks := checks + 1;
    expected_error := false;
    BEGIN
      PERFORM public.nikon_register_password(missing_name, encoded_hash, second_token, now() + interval '9 days');
    EXCEPTION WHEN raise_exception THEN
      IF SQLERRM <> 'INVALID_PASSWORD_ACCOUNT' THEN RAISE; END IF; expected_error := true;
    END;
    IF NOT expected_error OR (SELECT count(*) FROM public.nikon_users) <> before_users THEN
      RAISE EXCEPTION 'ASSERT 19: out-of-contract session expiry must not create an account';
    END IF;
    checks := checks + 1;
    UPDATE public.nikon_password_credentials SET password_hash = next_hash WHERE user_id = account_id;
    SELECT login_count INTO before_count FROM public.nikon_users WHERE id = account_id;
    expected_error := false;
    BEGIN
      PERFORM public.nikon_sign_in_password(account_name, encoded_hash, second_token, now() + interval '7 days');
    EXCEPTION WHEN raise_exception THEN
      IF SQLERRM <> 'USER_LOGIN_FAILED' THEN RAISE; END IF; expected_error := true;
    END;
    IF NOT expected_error OR (SELECT login_count FROM public.nikon_users WHERE id = account_id) <> before_count THEN
      RAISE EXCEPTION 'ASSERT 20: hash changed after server verification must be rejected under lock';
    END IF;
    checks := checks + 1;
    item := public.nikon_sign_in_password(account_name, next_hash, second_token, now() + interval '7 days');
    IF (item->>'loginCount')::bigint <> before_count + 1 OR public.nikon_user_session(second_token) IS NULL THEN
      RAISE EXCEPTION 'ASSERT 21: the current verified hash remains usable';
    END IF;
    checks := checks + 1;
    -- These global counter edits are inside the same forced rollback and are restored before completion.
    INSERT INTO public.nikon_login_attempts(attempt_key,attempts,window_start) VALUES('password-login-global',0,now()),('password-register-global',0,now())
      ON CONFLICT(attempt_key) DO UPDATE SET attempts = 0, window_start = now();
    FOR idx IN 1..8 LOOP
      IF NOT public.nikon_password_auth_allowed(fixture_attempt_key,false) THEN RAISE EXCEPTION 'ASSERT 22: normal login attempt budget'; END IF;
    END LOOP;
    IF public.nikon_password_auth_allowed(fixture_attempt_key,false) THEN RAISE EXCEPTION 'ASSERT 22: ninth account login attempt must be throttled'; END IF;
    checks := checks + 1;
    FOR idx IN 1..8 LOOP
      IF NOT public.nikon_password_auth_allowed(fixture_attempt_key,true) THEN RAISE EXCEPTION 'ASSERT 23: registration attempt budget is distinct'; END IF;
    END LOOP;
    IF public.nikon_password_auth_allowed(fixture_attempt_key,true) THEN RAISE EXCEPTION 'ASSERT 23: ninth registration attempt must be throttled'; END IF;
    checks := checks + 1;
    UPDATE public.nikon_login_attempts SET attempts = 600 WHERE attempt_key = 'password-login-global';
    IF public.nikon_password_auth_allowed(nonce || nonce,false) THEN RAISE EXCEPTION 'ASSERT 24: global cross-instance login limit must apply to new names'; END IF;
    UPDATE public.nikon_login_attempts SET attempts = 120 WHERE attempt_key = 'password-register-global';
    IF public.nikon_password_auth_allowed(nonce || nonce,true) THEN RAISE EXCEPTION 'ASSERT 24: global registration limit must apply to new names'; END IF;
    checks := checks + 1;
    IF NOT EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
      WHERE n.nspname = 'public' AND c.relname = 'nikon_password_credentials' AND c.relrowsecurity)
      OR EXISTS (SELECT 1 FROM unnest(ARRAY['anon','authenticated']) AS role_name
        WHERE has_table_privilege(role_name,'public.nikon_password_credentials','SELECT,INSERT,UPDATE,DELETE'))
      OR NOT has_table_privilege('service_role','public.nikon_password_credentials','SELECT,INSERT,UPDATE,DELETE') THEN
      RAISE EXCEPTION 'ASSERT 25: password credentials must be service-only with RLS';
    END IF;
    checks := checks + 1;
    IF EXISTS (SELECT 1 FROM unnest(ARRAY['anon','authenticated']) AS role_name CROSS JOIN unnest(ARRAY[
      'public.nikon_password_auth_allowed(text,boolean)','public.nikon_register_password(text,text,text,timestamptz)',
      'public.nikon_sign_in_password(text,text,text,timestamptz)']) AS function_name
      WHERE has_function_privilege(role_name,function_name,'EXECUTE')) THEN
      RAISE EXCEPTION 'ASSERT 26: public clients cannot invoke password registration/session RPCs';
    END IF;
    checks := checks + 1;
    IF EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
      WHERE n.nspname = 'public' AND p.proname IN ('nikon_register_password','nikon_sign_in_password','nikon_password_auth_allowed')
        AND p.prosecdef) THEN
      RAISE EXCEPTION 'ASSERT 27: password functions must not elevate privileges as security definer';
    END IF;
    checks := checks + 1;
    RAISE EXCEPTION USING ERRCODE = 'NPA01', MESSAGE = 'nikon_password_acceptance_force_rollback';
  EXCEPTION WHEN SQLSTATE 'NPA01' THEN
    IF SQLERRM <> 'nikon_password_acceptance_force_rollback' THEN RAISE; END IF; rollback_seen := true;
  END;
  IF NOT rollback_seen OR account_id IS NULL
    OR EXISTS (SELECT 1 FROM public.nikon_users WHERE id = account_id)
    OR EXISTS (SELECT 1 FROM public.nikon_password_credentials WHERE username IN (account_name,missing_name))
    OR EXISTS (SELECT 1 FROM public.nikon_user_sessions WHERE user_id = account_id OR token_hash IN (first_token,second_token,expired_token))
    OR EXISTS (SELECT 1 FROM public.nikon_login_attempts WHERE attempt_key IN (
      'password-login:' || fixture_attempt_key,'password-register:' || fixture_attempt_key,
      'password-login:' || nonce || nonce,'password-register:' || nonce || nonce)) THEN
    RAISE EXCEPTION 'ASSERT 28: rollback must retain no synthetic user, credential, session or per-account attempt';
  END IF;
  checks := checks + 1;
  IF checks <> 28 THEN RAISE EXCEPTION 'Password acceptance assertion count mismatch'; END IF;
  RAISE NOTICE 'PASS: % password database acceptance assertions; all fixtures rolled back', checks;
END;
$nikon_password_acceptance$;
