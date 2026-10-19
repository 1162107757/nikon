BEGIN;
CREATE TABLE IF NOT EXISTS public.nikon_personal_presets(
 user_id uuid NOT NULL REFERENCES public.nikon_users(id), sha256 text NOT NULL CHECK(sha256~'^[a-f0-9]{64}$'),
 metadata jsonb NOT NULL, revision integer NOT NULL DEFAULT 1, deleted boolean NOT NULL DEFAULT false,
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(user_id,sha256));
CREATE TABLE IF NOT EXISTS public.nikon_personal_uploads(user_id uuid NOT NULL REFERENCES public.nikon_users(id),created_at timestamptz NOT NULL DEFAULT now());
CREATE INDEX IF NOT EXISTS nikon_personal_uploads_user_time ON public.nikon_personal_uploads(user_id,created_at);
ALTER TABLE public.nikon_personal_presets ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nikon_personal_uploads ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.nikon_personal_presets,public.nikon_personal_uploads FROM PUBLIC,anon,authenticated;
GRANT ALL ON public.nikon_personal_presets,public.nikon_personal_uploads TO service_role;
CREATE OR REPLACE FUNCTION public.nikon_personal_library(p_action text,p_user_id uuid,p_hash text DEFAULT NULL,p_body jsonb DEFAULT '{}')
RETURNS jsonb LANGUAGE plpgsql SECURITY INVOKER SET search_path=public,pg_temp AS $$
DECLARE row nikon_personal_presets%ROWTYPE; items jsonb; expected integer; used bigint; old_bytes bigint;
BEGIN
 PERFORM 1 FROM nikon_users WHERE id=p_user_id AND status='active' FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'USER_LOGIN_REQUIRED'; END IF;
 IF p_action='list' THEN
  IF coalesce(p_body->>'after','')<>'' AND p_body->>'after'!~'^[a-f0-9]{64}$' THEN RAISE EXCEPTION 'INVALID_PRESET'; END IF;
  SELECT coalesce(jsonb_agg(item ORDER BY sha256),'[]'::jsonb) INTO items FROM
   (SELECT sha256,metadata||jsonb_build_object('sha256',sha256,'revision',revision,'deleted',deleted,'updatedAt',updated_at) item
    FROM nikon_personal_presets WHERE user_id=p_user_id AND sha256>coalesce(p_body->>'after','') ORDER BY sha256 LIMIT 100) q;
  RETURN jsonb_build_object('items',items);
 END IF;
 IF p_hash IS NULL OR p_hash!~'^[a-f0-9]{64}$' THEN RAISE EXCEPTION 'INVALID_PRESET'; END IF;
 SELECT * INTO row FROM nikon_personal_presets WHERE user_id=p_user_id AND sha256=p_hash;
 IF p_action='get' THEN
  IF row.user_id IS NULL OR row.deleted THEN RAISE EXCEPTION 'NOT_FOUND'; END IF;
  RETURN row.metadata||jsonb_build_object('sha256',row.sha256,'revision',row.revision);
 END IF;
 IF p_action='reserve' THEN
  IF (SELECT count(*) FROM nikon_personal_uploads WHERE user_id=p_user_id AND created_at>now()-interval '1 hour')>=120
   OR (SELECT count(*) FROM nikon_personal_uploads WHERE user_id=p_user_id AND created_at>now()-interval '1 day')>=500 THEN RAISE EXCEPTION 'UPLOAD_QUOTA'; END IF;
  INSERT INTO nikon_personal_uploads(user_id) VALUES(p_user_id);
  IF coalesce((p_body->>'byteLength')::bigint,0) NOT BETWEEN 1 AND 1048576 THEN RAISE EXCEPTION 'INVALID_FILE'; END IF;
  SELECT coalesce(sum((metadata->>'byteLength')::bigint),0) INTO used FROM nikon_personal_presets WHERE user_id=p_user_id;
  old_bytes:=coalesce((row.metadata->>'byteLength')::bigint,0);
  IF used-old_bytes+(p_body->>'byteLength')::bigint>134217728 OR
   (row.user_id IS NULL AND (SELECT count(*) FROM nikon_personal_presets WHERE user_id=p_user_id)>=5000) THEN RAISE EXCEPTION 'PERSONAL_LIBRARY_FULL'; END IF;
  RETURN '{"ok":true}';
 END IF;
 expected:=coalesce((p_body->>'expectedRevision')::integer,0);
 IF p_action='put' THEN
  SELECT coalesce(sum((metadata->>'byteLength')::bigint),0) INTO used FROM nikon_personal_presets WHERE user_id=p_user_id;
  IF used-coalesce((row.metadata->>'byteLength')::bigint,0)+coalesce((p_body->'metadata'->>'byteLength')::bigint,0)>134217728
   OR (row.user_id IS NULL AND (SELECT count(*) FROM nikon_personal_presets WHERE user_id=p_user_id)>=5000) THEN RAISE EXCEPTION 'PERSONAL_LIBRARY_FULL'; END IF;
  IF row.user_id IS NOT NULL AND NOT row.deleted AND row.metadata=p_body->'metadata' THEN RETURN jsonb_build_object('revision',row.revision); END IF;
  IF expected<>coalesce(row.revision,0) THEN RAISE EXCEPTION 'REVISION_CONFLICT'; END IF;
  IF jsonb_typeof(p_body->'metadata') IS DISTINCT FROM 'object' OR coalesce((p_body->'metadata'->>'byteLength')::bigint,0) NOT BETWEEN 1 AND 1048576 THEN RAISE EXCEPTION 'INVALID_FILE'; END IF;
  INSERT INTO nikon_personal_presets(user_id,sha256,metadata) VALUES(p_user_id,p_hash,p_body->'metadata')
   ON CONFLICT(user_id,sha256) DO UPDATE SET metadata=excluded.metadata,revision=nikon_personal_presets.revision+1,deleted=false,updated_at=clock_timestamp()
   RETURNING * INTO row;
  RETURN jsonb_build_object('revision',row.revision);
 ELSIF p_action='delete' THEN
  IF row.user_id IS NULL OR row.deleted THEN RETURN '{"ok":true}'; END IF;
  IF expected<>row.revision THEN RAISE EXCEPTION 'REVISION_CONFLICT'; END IF;
  UPDATE nikon_personal_presets SET deleted=true,revision=revision+1,updated_at=clock_timestamp() WHERE user_id=p_user_id AND sha256=p_hash;
  RETURN '{"ok":true}';
 END IF;
 RAISE EXCEPTION 'INVALID_PRESET';
END $$;
REVOKE ALL ON FUNCTION public.nikon_personal_library(text,uuid,text,jsonb) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.nikon_personal_library(text,uuid,text,jsonb) TO service_role;
NOTIFY pgrst,'reload schema';
COMMIT;
