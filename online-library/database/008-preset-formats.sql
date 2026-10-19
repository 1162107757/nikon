-- Add researched NP3 0310 file metadata without rewriting historical snapshots or
-- replacing the community dispatcher (which also carries comment/avatar changes).
BEGIN;

CREATE OR REPLACE FUNCTION public.nikon_preset_metadata_valid(p_file jsonb)
RETURNS boolean LANGUAGE sql IMMUTABLE SECURITY INVOKER SET search_path=public,pg_temp AS $$
 SELECT coalesce(
  (coalesce(p_file->>'fileFormat','NCP')='NCP' AND p_file->>'ncpVersion'='0100' AND coalesce(p_file->>'extension','ncp')='ncp')
  OR (p_file->>'fileFormat'='NP3' AND p_file->>'ncpVersion'='0310' AND p_file->>'extension'='np3'),false);
$$;
REVOKE ALL ON FUNCTION public.nikon_preset_metadata_valid(jsonb) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.nikon_preset_metadata_valid(jsonb) TO service_role;

-- Patch only stable preset fragments, and fail closed if the deployed definition
-- is unexpected. Re-running this migration leaves the current definitions intact.
DO $patch$
DECLARE source text; old_fragment text; new_fragment text;
BEGIN
 source:=pg_get_functiondef('public.nikon_community(text,uuid,uuid,jsonb)'::regprocedure);
 old_fragment:=$old$OR (asset->>'kind'='ncp' AND ((asset->>'byteLength')::integer>1048576 OR asset->>'ncpVersion'<>'0100'))$old$;
 new_fragment:=$new$OR (asset->>'kind'='ncp' AND ((asset->>'byteLength')::integer>1048576 OR NOT public.nikon_preset_metadata_valid(asset)))$new$;
 IF strpos(source,new_fragment)=0 THEN
  IF (length(source)-length(replace(source,old_fragment,'')))/length(old_fragment)<>1 THEN RAISE EXCEPTION 'PRESET_FORMAT_COMMUNITY_PATCH_MISMATCH'; END IF;
  EXECUTE replace(source,old_fragment,new_fragment);
 END IF;

 source:=pg_get_functiondef('public.nikon_community_public_rows()'::regprocedure);
 old_fragment:=$old$'fileUrl',s.published->'file'->>'url'$old$;
 new_fragment:=$new$'fileFormat',coalesce(s.published->'file'->>'fileFormat','NCP'),'fileUrl',s.published->'file'->>'url'$new$;
 IF strpos(source,new_fragment)=0 THEN
  IF (length(source)-length(replace(source,old_fragment,'')))/length(old_fragment)<>1 THEN RAISE EXCEPTION 'PRESET_FORMAT_COMMUNITY_FEED_PATCH_MISMATCH'; END IF;
  source:=replace(source,old_fragment,new_fragment);
 END IF;
 old_fragment:=$old$'fileUrl',p.published->'file'->>'url'$old$;
 new_fragment:=$new$'fileFormat',coalesce(p.published->'file'->>'fileFormat','NCP'),'fileUrl',p.published->'file'->>'url'$new$;
 IF strpos(source,new_fragment)=0 THEN
  IF (length(source)-length(replace(source,old_fragment,'')))/length(old_fragment)<>1 THEN RAISE EXCEPTION 'PRESET_FORMAT_CURATED_FEED_PATCH_MISMATCH'; END IF;
  source:=replace(source,old_fragment,new_fragment);
 END IF;
 EXECUTE source;

 source:=pg_get_functiondef('public.nikon_community_submission_view(uuid)'::regprocedure);
 old_fragment:=$old$jsonb_build_object('id',s.id,'author'$old$;
 new_fragment:=$new$jsonb_build_object('id',s.id,'fileFormat',coalesce(s.draft->'file'->>'fileFormat','NCP'),'author'$new$;
 IF strpos(source,new_fragment)=0 THEN
  IF (length(source)-length(replace(source,old_fragment,'')))/length(old_fragment)<>1 THEN RAISE EXCEPTION 'PRESET_FORMAT_SUBMISSION_PATCH_MISMATCH'; END IF;
  EXECUTE replace(source,old_fragment,new_fragment);
 END IF;

 source:=pg_get_functiondef('public.nikon_catalog_manifest()'::regprocedure);
 old_fragment:=$old$'fileUrl',published->'file'->>'url'$old$;
 new_fragment:=$new$'fileFormat',coalesce(published->'file'->>'fileFormat','NCP'),'fileUrl',published->'file'->>'url'$new$;
 IF strpos(source,new_fragment)=0 THEN
  IF (length(source)-length(replace(source,old_fragment,'')))/length(old_fragment)<>1 THEN RAISE EXCEPTION 'PRESET_FORMAT_CATALOG_PATCH_MISMATCH'; END IF;
  EXECUTE replace(source,old_fragment,new_fragment);
 END IF;
END;
$patch$;

NOTIFY pgrst, 'reload schema';
COMMIT;
