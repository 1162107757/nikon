-- Both operations lock the administrator first, so an in-flight old-password login
-- cannot create a session after a successful password change has revoked them.
CREATE OR REPLACE FUNCTION public.nikon_sign_in_admin(
  p_username text, p_verified_hash text, p_token_hash text, p_csrf_token text, p_expires_at timestamptz
) RETURNS jsonb LANGUAGE plpgsql SECURITY INVOKER SET search_path = public, pg_temp AS $$
DECLARE admin_row nikon_admins%ROWTYPE;
BEGIN
  SELECT * INTO admin_row FROM nikon_admins WHERE username = p_username FOR UPDATE;
  IF NOT FOUND OR admin_row.disabled OR admin_row.password_hash IS DISTINCT FROM p_verified_hash THEN
    RAISE EXCEPTION 'LOGIN_FAILED';
  END IF;
  IF p_token_hash IS NULL OR p_token_hash !~ '^[a-f0-9]{64}$'
    OR p_csrf_token IS NULL OR p_csrf_token !~ '^[A-Za-z0-9_-]{43}$'
    OR p_expires_at IS NULL OR p_expires_at <= clock_timestamp() OR p_expires_at > clock_timestamp() + interval '8 hours' THEN
    RAISE EXCEPTION 'LOGIN_FAILED';
  END IF;
  DELETE FROM nikon_sessions WHERE expires_at <= clock_timestamp();
  INSERT INTO nikon_sessions(token_hash, username, csrf_token, expires_at)
    VALUES (p_token_hash, p_username, p_csrf_token, p_expires_at);
  RETURN jsonb_build_object('ok', true);
END;
$$;

CREATE OR REPLACE FUNCTION public.nikon_change_admin_password(
  p_username text, p_session_hash text, p_verified_hash text, p_password_hash text
) RETURNS jsonb LANGUAGE plpgsql SECURITY INVOKER SET search_path = public, pg_temp AS $$
DECLARE admin_row nikon_admins%ROWTYPE;
BEGIN
  SELECT * INTO admin_row FROM nikon_admins WHERE username = p_username FOR UPDATE;
  IF NOT FOUND OR admin_row.disabled THEN RAISE EXCEPTION 'LOGIN_REQUIRED'; END IF;
  PERFORM 1 FROM nikon_sessions WHERE token_hash = p_session_hash AND username = p_username
    AND expires_at > clock_timestamp() FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'LOGIN_REQUIRED'; END IF;
  IF admin_row.password_hash IS DISTINCT FROM p_verified_hash THEN RAISE EXCEPTION 'PASSWORD_CONFLICT'; END IF;
  IF p_password_hash IS NULL OR p_password_hash !~ '^scrypt:32768:8:1:[a-f0-9]{32}:[a-f0-9]{128}$'
    OR p_password_hash = p_verified_hash THEN RAISE EXCEPTION 'PASSWORD_CONFLICT'; END IF;
  UPDATE nikon_admins SET password_hash = p_password_hash WHERE username = p_username;
  DELETE FROM nikon_sessions WHERE username = p_username;
  RETURN jsonb_build_object('ok', true);
END;
$$;

REVOKE ALL ON FUNCTION public.nikon_sign_in_admin(text,text,text,text,timestamptz),
  public.nikon_change_admin_password(text,text,text,text) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.nikon_sign_in_admin(text,text,text,text,timestamptz),
  public.nikon_change_admin_password(text,text,text,text) TO service_role;
