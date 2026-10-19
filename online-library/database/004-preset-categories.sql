-- Add an optional category to the public client manifest. Historical snapshots remain immutable;
-- entries created before categories were introduced expose an empty category (= uncategorized).
-- Keep publication ordering, size/count enforcement, SECURITY INVOKER and service-only ACLs.
CREATE OR REPLACE FUNCTION public.nikon_catalog_manifest()
RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path = public, pg_temp AS $$
  SELECT jsonb_build_object('version',1,'presets',coalesce(jsonb_agg(
    jsonb_build_object('id',id::text,'name',published->>'name',
      'description',coalesce(published->>'description',''),
      'category',coalesce(published->>'category',''),
      'compatibleModels',coalesce(published->'compatibleModels','[]'::jsonb),
      'fileUrl',published->'file'->>'url','sha256',published->'file'->>'sha256',
      'byteLength',(published->'file'->>'byteLength')::integer,
      'previewUrl',coalesce(published->'preview'->>'url',''))
    ORDER BY coalesce((published->>'sortOrder')::integer,0),id),'[]'::jsonb))
  FROM nikon_presets WHERE status = 'published';
$$;

REVOKE ALL ON FUNCTION public.nikon_catalog_manifest() FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.nikon_catalog_manifest() TO service_role;
NOTIFY pgrst, 'reload schema';
