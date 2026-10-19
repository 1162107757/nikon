-- Only the server's service_role may read or change administrative data.
-- Public catalog access goes through the HTTP server, not the tables.
CREATE TABLE IF NOT EXISTS public.nikon_admins (
  username text PRIMARY KEY,
  password_hash text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  disabled boolean NOT NULL DEFAULT false
);
CREATE TABLE IF NOT EXISTS public.nikon_sessions (
  token_hash text PRIMARY KEY,
  username text NOT NULL REFERENCES public.nikon_admins(username),
  csrf_token text NOT NULL,
  expires_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS nikon_sessions_expiry ON public.nikon_sessions(expires_at);
CREATE TABLE IF NOT EXISTS public.nikon_login_attempts (
  attempt_key text PRIMARY KEY,
  attempts integer NOT NULL DEFAULT 1,
  window_start timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS public.nikon_assets (
  id text PRIMARY KEY,
  payload jsonb NOT NULL,
  ever_published boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS public.nikon_presets (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  revision integer NOT NULL DEFAULT 1 CHECK (revision > 0),
  draft jsonb NOT NULL,
  published jsonb,
  published_revision integer,
  status text NOT NULL DEFAULT 'draft' CHECK (status IN ('draft','published','unpublished')),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  published_at timestamptz,
  CHECK (status <> 'published' OR (published IS NOT NULL AND published_revision IS NOT NULL))
);
CREATE TABLE IF NOT EXISTS public.nikon_preset_versions (
  preset_id uuid NOT NULL REFERENCES public.nikon_presets(id),
  revision integer NOT NULL,
  snapshot jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (preset_id, revision)
);

ALTER TABLE public.nikon_admins ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nikon_sessions ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nikon_login_attempts ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nikon_assets ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nikon_presets ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nikon_preset_versions ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.nikon_admins, public.nikon_sessions, public.nikon_login_attempts,
  public.nikon_assets, public.nikon_presets, public.nikon_preset_versions FROM PUBLIC, anon, authenticated;
GRANT ALL ON public.nikon_admins, public.nikon_sessions, public.nikon_login_attempts,
  public.nikon_assets, public.nikon_presets, public.nikon_preset_versions TO service_role;

CREATE OR REPLACE FUNCTION public.nikon_login_allowed(p_key text)
RETURNS boolean LANGUAGE plpgsql SECURITY INVOKER SET search_path = public, pg_temp AS $$
DECLARE attempt_count integer;
BEGIN
  DELETE FROM nikon_login_attempts WHERE window_start < now() - interval '1 day';
  INSERT INTO nikon_login_attempts(attempt_key) VALUES (p_key)
  ON CONFLICT (attempt_key) DO UPDATE SET
    attempts = CASE WHEN nikon_login_attempts.window_start < now() - interval '15 minutes'
      THEN 1 ELSE nikon_login_attempts.attempts + 1 END,
    window_start = CASE WHEN nikon_login_attempts.window_start < now() - interval '15 minutes'
      THEN now() ELSE nikon_login_attempts.window_start END
  RETURNING attempts INTO attempt_count;
  RETURN attempt_count <= 8;
END;
$$;

CREATE OR REPLACE FUNCTION public.nikon_save_preset(p_id uuid, p_expected_revision integer, p_draft jsonb)
RETURNS jsonb LANGUAGE plpgsql SECURITY INVOKER SET search_path = public, pg_temp AS $$
DECLARE current_row nikon_presets%ROWTYPE;
BEGIN
  IF jsonb_typeof(p_draft) <> 'object' OR coalesce(length(p_draft->>'name'),0) = 0 THEN
    RAISE EXCEPTION 'INVALID_PRESET';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM nikon_assets WHERE id = p_draft->'file'->>'id'
    AND payload = p_draft->'file' AND payload->>'kind' = 'ncp') THEN
    RAISE EXCEPTION 'INVALID_FILE';
  END IF;
  IF p_draft->'preview' IS NOT NULL AND p_draft->'preview' <> 'null'::jsonb AND
    NOT EXISTS (SELECT 1 FROM nikon_assets WHERE id = p_draft->'preview'->>'id'
      AND payload = p_draft->'preview' AND payload->>'kind' = 'preview') THEN
    RAISE EXCEPTION 'INVALID_PREVIEW';
  END IF;
  IF p_id IS NULL THEN
    INSERT INTO nikon_presets(draft) VALUES(p_draft) RETURNING * INTO current_row;
  ELSE
    SELECT * INTO current_row FROM nikon_presets WHERE id = p_id FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'NOT_FOUND'; END IF;
    IF p_expected_revision IS NULL OR current_row.revision <> p_expected_revision THEN
      RAISE EXCEPTION 'REVISION_CONFLICT';
    END IF;
    IF current_row.draft = p_draft THEN RETURN to_jsonb(current_row); END IF;
    UPDATE nikon_presets SET draft = p_draft, revision = revision + 1, updated_at = now()
      WHERE id = p_id RETURNING * INTO current_row;
  END IF;
  INSERT INTO nikon_preset_versions(preset_id,revision,snapshot)
    VALUES (current_row.id,current_row.revision,current_row.draft);
  RETURN to_jsonb(current_row);
END;
$$;

CREATE OR REPLACE FUNCTION public.nikon_catalog_manifest()
RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path = public, pg_temp AS $$
  SELECT jsonb_build_object('version',1,'presets',coalesce(jsonb_agg(
    jsonb_build_object('id',id::text,'name',published->>'name',
      'description',coalesce(published->>'description',''),
      'compatibleModels',coalesce(published->'compatibleModels','[]'::jsonb),
      'fileUrl',published->'file'->>'url','sha256',published->'file'->>'sha256',
      'byteLength',(published->'file'->>'byteLength')::integer,
      'previewUrl',coalesce(published->'preview'->>'url',''))
    ORDER BY coalesce((published->>'sortOrder')::integer,0),id),'[]'::jsonb))
  FROM nikon_presets WHERE status = 'published';
$$;

CREATE OR REPLACE FUNCTION public.nikon_publish_preset(p_id uuid, p_expected_revision integer, p_publish boolean)
RETURNS jsonb LANGUAGE plpgsql SECURITY INVOKER SET search_path = public, pg_temp AS $$
DECLARE current_row nikon_presets%ROWTYPE; manifest jsonb;
BEGIN
  -- Serializes publication only; drafts can be edited independently.
  PERFORM pg_advisory_xact_lock(1852403310, 1);
  SELECT * INTO current_row FROM nikon_presets WHERE id = p_id FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'NOT_FOUND'; END IF;
  IF p_expected_revision IS NULL OR current_row.revision <> p_expected_revision THEN
    RAISE EXCEPTION 'REVISION_CONFLICT';
  END IF;
  IF p_publish THEN
    UPDATE nikon_presets SET published = draft, published_revision = revision,
      status = 'published',published_at = now(),updated_at = now()
      WHERE id = p_id RETURNING * INTO current_row;
    SELECT nikon_catalog_manifest() INTO manifest;
    IF jsonb_array_length(manifest->'presets') > 256 THEN RAISE EXCEPTION 'CATALOG_FULL'; END IF;
    IF octet_length(convert_to(manifest::text,'UTF8')) > 524288 THEN RAISE EXCEPTION 'CATALOG_TOO_LARGE'; END IF;
    UPDATE nikon_assets SET ever_published = true WHERE id IN
      (current_row.draft->'file'->>'id', current_row.draft->'preview'->>'id');
  ELSE
    UPDATE nikon_presets SET status = CASE WHEN published IS NULL THEN 'draft' ELSE 'unpublished' END,
      updated_at = now() WHERE id = p_id RETURNING * INTO current_row;
  END IF;
  RETURN to_jsonb(current_row);
END;
$$;

REVOKE ALL ON FUNCTION public.nikon_login_allowed(text) FROM PUBLIC,anon,authenticated;
REVOKE ALL ON FUNCTION public.nikon_save_preset(uuid,integer,jsonb) FROM PUBLIC,anon,authenticated;
REVOKE ALL ON FUNCTION public.nikon_catalog_manifest() FROM PUBLIC,anon,authenticated;
REVOKE ALL ON FUNCTION public.nikon_publish_preset(uuid,integer,boolean) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.nikon_login_allowed(text), public.nikon_save_preset(uuid,integer,jsonb),
  public.nikon_catalog_manifest(), public.nikon_publish_preset(uuid,integer,boolean) TO service_role;
NOTIFY pgrst, 'reload schema';
