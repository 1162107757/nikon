-- Explicit real PostgreSQL acceptance; not part of the local Node runner.
-- Expected assertions: 24. Only random synthetic QQ identities/users/sessions are created.
-- The inner exception subtransaction rolls back every fixture and every RPC effect before finishing.
DO $nikon_user_acceptance$
DECLARE
  nonce text := replace(gen_random_uuid()::text, '-', '');
  openid_fixture text := upper(replace(gen_random_uuid()::text, '-', ''));
  first_hash text := nonce || replace(gen_random_uuid()::text, '-', '');
  second_hash text := nonce || replace(gen_random_uuid()::text, '-', '');
  next_hash text := nonce || replace(gen_random_uuid()::text, '-', '');
  expired_hash text := nonce || replace(gen_random_uuid()::text, '-', '');
  token_fixture text;
  user_id_fixture uuid;
  result jsonb; initial_user jsonb; disabled_user jsonb; page_result jsonb;
  expected_error boolean; rollback_seen boolean := false; checks integer := 0; idx integer;
BEGIN
  BEGIN
    IF EXISTS (SELECT 1 FROM public.nikon_user_identities WHERE app_id = '123456789' AND provider_subject = openid_fixture)
      OR EXISTS (SELECT 1 FROM public.nikon_user_sessions WHERE token_hash IN (first_hash, second_hash, next_hash, expired_hash)) THEN
      RAISE EXCEPTION 'ASSERT 01: synthetic fixture collision';
    END IF;
    checks := checks + 1;
    result := public.nikon_sign_in_qq('123456789', lower(openid_fixture), '__qq_acceptance_' || nonce,
      'https://q.qlogo.cn/acceptance', first_hash, now() + interval '7 days');
    user_id_fixture := (result->>'id')::uuid; initial_user := result;
    IF user_id_fixture IS NULL OR result->>'status' IS DISTINCT FROM 'active'
      OR (result->>'loginCount')::integer <> 1 OR result->>'nickname' IS DISTINCT FROM '__qq_acceptance_' || nonce THEN
      RAISE EXCEPTION 'ASSERT 02: verified first login must create one normal App user';
    END IF;
    checks := checks + 1;
    IF (SELECT count(*) FROM public.nikon_user_identities WHERE user_id = user_id_fixture) <> 1
      OR NOT EXISTS (SELECT 1 FROM public.nikon_user_identities WHERE user_id = user_id_fixture AND provider = 'qq'
        AND app_id = '123456789' AND provider_subject = openid_fixture) THEN
      RAISE EXCEPTION 'ASSERT 03: QQ identity must be bound and case-normalized';
    END IF;
    checks := checks + 1;
    IF (SELECT count(*) FROM public.nikon_user_sessions WHERE user_id = user_id_fixture) <> 1
      OR NOT EXISTS (SELECT 1 FROM public.nikon_user_sessions WHERE token_hash = first_hash AND user_id = user_id_fixture)
      OR public.nikon_user_session(first_hash)->'user'->>'id' IS DISTINCT FROM user_id_fixture::text THEN
      RAISE EXCEPTION 'ASSERT 04: user session must resolve through its one-way hash';
    END IF;
    checks := checks + 1;
    result := public.nikon_sign_in_qq('123456789', openid_fixture, '__qq_acceptance_next_' || nonce,
      '', second_hash, now() + interval '7 days');
    IF result->>'id' IS DISTINCT FROM user_id_fixture::text OR (result->>'loginCount')::integer <> 2
      OR result->>'nickname' IS DISTINCT FROM '__qq_acceptance_next_' || nonce
      OR (SELECT count(*) FROM public.nikon_user_identities WHERE user_id = user_id_fixture) <> 1 THEN
      RAISE EXCEPTION 'ASSERT 05: repeat QQ login must reuse the identity and update profile/count';
    END IF;
    checks := checks + 1;
    IF (SELECT count(*) FROM public.nikon_user_sessions WHERE user_id = user_id_fixture) <> 2
      OR (result->>'updatedAt')::timestamptz <= (initial_user->>'updatedAt')::timestamptz THEN
      RAISE EXCEPTION 'ASSERT 06: repeated login must issue a distinct session and advance the version';
    END IF;
    checks := checks + 1;
    initial_user := result;
    result := public.nikon_user_detail(user_id_fixture);
    IF result->>'qqOpenIdMasked' IS DISTINCT FROM left(openid_fixture, 4) || '…' || right(openid_fixture, 4)
      OR position(openid_fixture in result::text) > 0
      OR result ?| ARRAY['accessToken', 'access_token', 'provider_subject', 'token_hash'] THEN
      RAISE EXCEPTION 'ASSERT 07: admin detail must only disclose the masked QQ identifier';
    END IF;
    checks := checks + 1;
    page_result := public.nikon_users_page(nonce, 'all', 1, 20);
    IF (page_result->>'total')::integer <> 1 OR jsonb_array_length(page_result->'items') <> 1
      OR page_result->'items'->0->>'id' IS DISTINCT FROM user_id_fixture::text
      OR page_result->'items'->0 ?| ARRAY['qqOpenIdMasked', 'provider_subject', 'accessToken', 'token_hash'] THEN
      RAISE EXCEPTION 'ASSERT 08: list search must page only safe user fields';
    END IF;
    checks := checks + 1;
    IF (page_result->'summary'->>'total')::bigint <> (SELECT count(*) FROM public.nikon_users)
      OR (page_result->'summary'->>'active')::bigint <> (SELECT count(*) FROM public.nikon_users WHERE status = 'active')
      OR (page_result->'summary'->>'disabled')::bigint <> (SELECT count(*) FROM public.nikon_users WHERE status = 'disabled') THEN
      RAISE EXCEPTION 'ASSERT 09: summary counts must remain global when search filters items';
    END IF;
    checks := checks + 1;
    IF (public.nikon_users_page(nonce || '%', 'all', 1, 20)->>'total')::integer <> 0 THEN
      RAISE EXCEPTION 'ASSERT 10: wildcard input must be treated literally';
    END IF;
    checks := checks + 1;
    expected_error := false;
    BEGIN
      PERFORM public.nikon_set_user_status(user_id_fixture, 'disabled', (initial_user->>'updatedAt')::timestamptz - interval '1 microsecond');
    EXCEPTION WHEN raise_exception THEN
      IF SQLERRM <> 'USER_CONFLICT' THEN RAISE; END IF; expected_error := true;
    END;
    IF NOT expected_error OR (SELECT count(*) FROM public.nikon_user_sessions WHERE user_id = user_id_fixture) <> 2 THEN
      RAISE EXCEPTION 'ASSERT 11: stale status writes must leave sessions untouched';
    END IF;
    checks := checks + 1;
    disabled_user := public.nikon_set_user_status(user_id_fixture, 'disabled', (initial_user->>'updatedAt')::timestamptz);
    IF disabled_user->>'status' IS DISTINCT FROM 'disabled'
      OR (disabled_user->>'updatedAt')::timestamptz <= (initial_user->>'updatedAt')::timestamptz THEN
      RAISE EXCEPTION 'ASSERT 12: a status write must advance its complete PostgreSQL timestamp';
    END IF;
    checks := checks + 1;
    IF EXISTS (SELECT 1 FROM public.nikon_user_sessions WHERE user_id = user_id_fixture)
      OR public.nikon_user_session(first_hash) IS NOT NULL OR public.nikon_user_session(second_hash) IS NOT NULL THEN
      RAISE EXCEPTION 'ASSERT 13: disabling must revoke every ordinary user session atomically';
    END IF;
    checks := checks + 1;
    expected_error := false;
    BEGIN
      PERFORM public.nikon_sign_in_qq('123456789', openid_fixture, 'disabled overwrite', '', next_hash, now() + interval '7 days');
    EXCEPTION WHEN raise_exception THEN
      IF SQLERRM <> 'USER_DISABLED' THEN RAISE; END IF; expected_error := true;
    END;
    IF NOT expected_error OR (SELECT login_count FROM public.nikon_users WHERE id = user_id_fixture) <> 2
      OR EXISTS (SELECT 1 FROM public.nikon_user_sessions WHERE token_hash = next_hash) THEN
      RAISE EXCEPTION 'ASSERT 14: disabled login must not alter profile/count or create a session';
    END IF;
    checks := checks + 1;
    page_result := public.nikon_users_page(nonce, 'disabled', 1, 20);
    IF (page_result->>'total')::integer <> 1 OR page_result->'items'->0->>'status' IS DISTINCT FROM 'disabled' THEN
      RAISE EXCEPTION 'ASSERT 15: disabled filtering must expose the saved status';
    END IF;
    checks := checks + 1;
    expected_error := false;
    BEGIN
      PERFORM public.nikon_set_user_status(user_id_fixture, 'active', (initial_user->>'updatedAt')::timestamptz);
    EXCEPTION WHEN raise_exception THEN
      IF SQLERRM <> 'USER_CONFLICT' THEN RAISE; END IF; expected_error := true;
    END;
    IF NOT expected_error THEN RAISE EXCEPTION 'ASSERT 16: a stale enable must fail'; END IF;
    checks := checks + 1;
    result := public.nikon_set_user_status(user_id_fixture, 'active', (disabled_user->>'updatedAt')::timestamptz);
    IF result->>'status' IS DISTINCT FROM 'active' OR (result->>'loginCount')::integer <> 2
      OR public.nikon_user_session(first_hash) IS NOT NULL THEN
      RAISE EXCEPTION 'ASSERT 17: enabling must retain the account and never revive revoked tokens';
    END IF;
    checks := checks + 1;
    result := public.nikon_sign_in_qq('123456789', openid_fixture, '__qq_acceptance_final_' || nonce, '', next_hash, now() + interval '7 days');
    IF result->>'id' IS DISTINCT FROM user_id_fixture::text OR (result->>'loginCount')::integer <> 3
      OR public.nikon_user_session(next_hash) IS NULL
      OR public.nikon_user_session(next_hash)->'user' ? 'qqOpenIdMasked' THEN
      RAISE EXCEPTION 'ASSERT 18: fresh login after enabling must reuse account and a safe public profile';
    END IF;
    checks := checks + 1;
    INSERT INTO public.nikon_user_sessions(token_hash, user_id, expires_at) VALUES(expired_hash, user_id_fixture, now() - interval '1 second');
    IF public.nikon_user_session(expired_hash) IS NOT NULL THEN RAISE EXCEPTION 'ASSERT 19: expired bearer session must be rejected'; END IF;
    checks := checks + 1;
    FOR idx IN 1..11 LOOP
      token_fixture := nonce || replace(gen_random_uuid()::text, '-', '');
      PERFORM public.nikon_sign_in_qq('123456789', openid_fixture, '__qq_acceptance_final_' || nonce, '', token_fixture, now() + interval '7 days');
    END LOOP;
    IF (SELECT count(*) FROM public.nikon_user_sessions WHERE user_id = user_id_fixture) <> 10
      OR public.nikon_user_session(token_fixture) IS NULL THEN
      RAISE EXCEPTION 'ASSERT 20: per-user retained sessions must be capped while keeping the just-issued token';
    END IF;
    checks := checks + 1;
    expected_error := false;
    BEGIN
      PERFORM public.nikon_users_page('', 'admin', 1, 20);
    EXCEPTION WHEN raise_exception THEN
      IF SQLERRM <> 'INVALID_USER_QUERY' THEN RAISE; END IF; expected_error := true;
    END;
    IF NOT expected_error THEN RAISE EXCEPTION 'ASSERT 21: RPC must reject out-of-contract query conditions'; END IF;
    checks := checks + 1;
    IF (SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
      WHERE n.nspname = 'public' AND c.relname IN ('nikon_users', 'nikon_user_identities', 'nikon_user_sessions') AND c.relrowsecurity) <> 3
      OR EXISTS (SELECT 1 FROM unnest(ARRAY['anon','authenticated']) AS role_name
        CROSS JOIN unnest(ARRAY['public.nikon_users','public.nikon_user_identities','public.nikon_user_sessions']) AS table_name
        WHERE has_table_privilege(role_name, table_name, 'SELECT,INSERT,UPDATE,DELETE')) THEN
      RAISE EXCEPTION 'ASSERT 22: user, identity and session tables must enforce RLS and deny public roles';
    END IF;
    checks := checks + 1;
    IF EXISTS (SELECT 1 FROM unnest(ARRAY['anon','authenticated']) AS role_name CROSS JOIN unnest(ARRAY[
      'public.nikon_user_detail(uuid)', 'public.nikon_users_page(text,text,integer,integer)',
      'public.nikon_set_user_status(uuid,text,timestamptz)', 'public.nikon_sign_in_qq(text,text,text,text,text,timestamptz)',
      'public.nikon_user_session(text)']) AS function_name WHERE has_function_privilege(role_name, function_name, 'EXECUTE')) THEN
      RAISE EXCEPTION 'ASSERT 23: only the service role may invoke user RPCs';
    END IF;
    checks := checks + 1;
    RAISE EXCEPTION USING ERRCODE = 'NQU01', MESSAGE = 'nikon_user_acceptance_force_rollback';
  EXCEPTION WHEN SQLSTATE 'NQU01' THEN
    IF SQLERRM <> 'nikon_user_acceptance_force_rollback' THEN RAISE; END IF;
    rollback_seen := true;
  END;
  IF NOT rollback_seen OR user_id_fixture IS NULL
    OR EXISTS (SELECT 1 FROM public.nikon_users WHERE id = user_id_fixture)
    OR EXISTS (SELECT 1 FROM public.nikon_user_identities WHERE provider_subject = openid_fixture)
    OR EXISTS (SELECT 1 FROM public.nikon_user_sessions WHERE user_id = user_id_fixture OR token_hash IN (first_hash, second_hash, next_hash, expired_hash)) THEN
    RAISE EXCEPTION 'ASSERT 24: forced rollback must retain no synthetic user, identity or session';
  END IF;
  checks := checks + 1;
  IF checks <> 24 THEN RAISE EXCEPTION 'User acceptance assertion count mismatch'; END IF;
  RAISE NOTICE 'PASS: % user database acceptance assertions; all fixtures rolled back', checks;
END;
$nikon_user_acceptance$;
