-- Explicit real PostgreSQL acceptance only; default Node tests never execute this file.
-- Expected assertions: 20. All fixture writes are rolled back inside an exception subtransaction.
-- No real preset, storage object, administrator, credential, or user row is read/changed.
DO $nikon_category_acceptance$
DECLARE
  nonce text := replace(gen_random_uuid()::text, '-', '');
  file_hash text := nonce || replace(gen_random_uuid()::text, '-', '');
  file_id text := 'ncp-' || file_hash;
  fixture_id uuid;
  file_payload jsonb;
  legacy_draft jsonb;
  category_draft jsonb;
  clear_draft jsonb;
  result jsonb;
  item jsonb;
  expected_error boolean;
  rollback_seen boolean := false;
  checks integer := 0;
BEGIN
  BEGIN
    IF EXISTS (SELECT 1 FROM public.nikon_assets WHERE id = file_id) THEN
      RAISE EXCEPTION 'ASSERT 01: synthetic fixture collision';
    END IF;
    checks := checks + 1;
    file_payload := jsonb_build_object('id', file_id, 'kind', 'ncp', 'sha256', file_hash,
      'byteLength', 52, 'ncpVersion', '0100', 'originalName', 'SYNTHETIC-CATEGORY.NCP',
      'storagePath', 'ncp/' || file_hash || '.ncp',
      'url', 'https://acceptance.invalid/files/' || file_id || '.ncp');
    INSERT INTO public.nikon_assets(id, payload) VALUES (file_id, file_payload);
    legacy_draft := jsonb_build_object('name', '__category_acceptance_' || nonce,
      'description', 'synthetic legacy preset without category', 'compatibleModels', '[]'::jsonb,
      'sortOrder', 0, 'file', file_payload, 'preview', null);
    result := public.nikon_save_preset(NULL::uuid, NULL::integer, legacy_draft);
    fixture_id := (result->>'id')::uuid;
    IF fixture_id IS NULL OR (result->>'revision')::integer IS DISTINCT FROM 1
      OR result->>'status' IS DISTINCT FROM 'draft' OR result->'draft' IS DISTINCT FROM legacy_draft
      OR (SELECT count(*) FROM public.nikon_preset_versions WHERE preset_id = fixture_id) <> 1 THEN
      RAISE EXCEPTION 'ASSERT 02: saving a legacy snapshot must retain the original data';
    END IF;
    checks := checks + 1;
    IF EXISTS (SELECT 1 FROM jsonb_array_elements(public.nikon_catalog_manifest()->'presets') e(value)
      WHERE e.value->>'id' = fixture_id::text) THEN
      RAISE EXCEPTION 'ASSERT 03: unpublished draft must not appear in catalog';
    END IF;
    checks := checks + 1;

    PERFORM public.nikon_publish_preset(fixture_id, 1, true);
    SELECT e.value INTO item FROM jsonb_array_elements(public.nikon_catalog_manifest()->'presets') e(value)
      WHERE e.value->>'id' = fixture_id::text;
    IF item IS NULL OR item->>'category' IS DISTINCT FROM '' OR item->>'previewUrl' IS DISTINCT FROM ''
      OR item->>'name' IS DISTINCT FROM legacy_draft->>'name' OR item->>'fileUrl' IS DISTINCT FROM file_payload->>'url'
      OR item->>'sha256' IS DISTINCT FROM file_hash OR (item->>'byteLength')::integer IS DISTINCT FROM 52
      OR item->'compatibleModels' IS DISTINCT FROM '[]'::jsonb THEN
      RAISE EXCEPTION 'ASSERT 04: old published entries must expose an empty category without losing existing fields';
    END IF;
    checks := checks + 1;
    IF NOT EXISTS (SELECT 1 FROM public.nikon_presets WHERE id = fixture_id AND published = legacy_draft AND draft = legacy_draft)
      OR NOT EXISTS (SELECT 1 FROM public.nikon_preset_versions WHERE preset_id = fixture_id AND revision = 1 AND NOT snapshot ? 'category') THEN
      RAISE EXCEPTION 'ASSERT 05: catalog fallback must not rewrite historical snapshots';
    END IF;
    checks := checks + 1;

    category_draft := legacy_draft || jsonb_build_object('category', '胶片');
    result := public.nikon_save_preset(fixture_id, 1, category_draft);
    IF (result->>'revision')::integer IS DISTINCT FROM 2 OR result->'draft' IS DISTINCT FROM category_draft
      OR result->'published' IS DISTINCT FROM legacy_draft OR result->>'status' IS DISTINCT FROM 'published' THEN
      RAISE EXCEPTION 'ASSERT 06: category edit must create a new draft revision while preserving the published snapshot';
    END IF;
    checks := checks + 1;
    SELECT e.value INTO item FROM jsonb_array_elements(public.nikon_catalog_manifest()->'presets') e(value)
      WHERE e.value->>'id' = fixture_id::text;
    IF item->>'category' IS DISTINCT FROM '' THEN RAISE EXCEPTION 'ASSERT 07: draft category must stay private'; END IF;
    checks := checks + 1;
    IF (SELECT count(*) FROM public.nikon_preset_versions WHERE preset_id = fixture_id) <> 2
      OR NOT EXISTS (SELECT 1 FROM public.nikon_preset_versions WHERE preset_id = fixture_id AND revision = 2 AND snapshot = category_draft) THEN
      RAISE EXCEPTION 'ASSERT 08: new category revision must be retained in version history';
    END IF;
    checks := checks + 1;
    expected_error := false;
    BEGIN
      PERFORM public.nikon_publish_preset(fixture_id, 1, true);
    EXCEPTION WHEN raise_exception THEN
      IF SQLERRM <> 'REVISION_CONFLICT' THEN RAISE; END IF;
      expected_error := true;
    END;
    IF NOT expected_error OR NOT EXISTS (SELECT 1 FROM public.nikon_presets WHERE id = fixture_id AND published = legacy_draft) THEN
      RAISE EXCEPTION 'ASSERT 09: stale publication must not expose the new category';
    END IF;
    checks := checks + 1;
    PERFORM public.nikon_publish_preset(fixture_id, 2, true);
    SELECT e.value INTO item FROM jsonb_array_elements(public.nikon_catalog_manifest()->'presets') e(value)
      WHERE e.value->>'id' = fixture_id::text;
    IF item->>'category' IS DISTINCT FROM '胶片'
      OR (SELECT count(*) FROM jsonb_object_keys(item)) <> 9
      OR item ?| ARRAY['draft','published','sortOrder','storagePath','revision']
      OR (SELECT count(*) FROM public.nikon_preset_versions WHERE preset_id = fixture_id) <> 2 THEN
      RAISE EXCEPTION 'ASSERT 10: explicit publication must expose only client fields with the chosen category';
    END IF;
    checks := checks + 1;

    clear_draft := legacy_draft || jsonb_build_object('category', '');
    result := public.nikon_save_preset(fixture_id, 2, clear_draft);
    SELECT e.value INTO item FROM jsonb_array_elements(public.nikon_catalog_manifest()->'presets') e(value)
      WHERE e.value->>'id' = fixture_id::text;
    IF (result->>'revision')::integer IS DISTINCT FROM 3 OR item->>'category' IS DISTINCT FROM '胶片' THEN
      RAISE EXCEPTION 'ASSERT 11: clearing draft category must not silently change the live category';
    END IF;
    checks := checks + 1;
    expected_error := false;
    BEGIN
      PERFORM public.nikon_save_preset(fixture_id, 2, category_draft);
    EXCEPTION WHEN raise_exception THEN
      IF SQLERRM <> 'REVISION_CONFLICT' THEN RAISE; END IF;
      expected_error := true;
    END;
    IF NOT expected_error OR NOT EXISTS (SELECT 1 FROM public.nikon_presets WHERE id = fixture_id AND revision = 3 AND draft = clear_draft) THEN
      RAISE EXCEPTION 'ASSERT 12: stale category edit must not replace a newer draft';
    END IF;
    checks := checks + 1;
    PERFORM public.nikon_publish_preset(fixture_id, 3, true);
    SELECT e.value INTO item FROM jsonb_array_elements(public.nikon_catalog_manifest()->'presets') e(value)
      WHERE e.value->>'id' = fixture_id::text;
    IF item->>'category' IS DISTINCT FROM '' THEN RAISE EXCEPTION 'ASSERT 13: explicitly published empty category must mean uncategorized'; END IF;
    checks := checks + 1;
    IF NOT EXISTS (SELECT 1 FROM public.nikon_preset_versions WHERE preset_id = fixture_id AND revision = 1 AND snapshot = legacy_draft)
      OR NOT EXISTS (SELECT 1 FROM public.nikon_preset_versions WHERE preset_id = fixture_id AND revision = 2 AND snapshot = category_draft)
      OR NOT EXISTS (SELECT 1 FROM public.nikon_preset_versions WHERE preset_id = fixture_id AND revision = 3 AND snapshot = clear_draft)
      OR NOT EXISTS (SELECT 1 FROM public.nikon_presets WHERE id = fixture_id AND published = clear_draft AND draft = clear_draft) THEN
      RAISE EXCEPTION 'ASSERT 14: every historical category snapshot must remain immutable';
    END IF;
    checks := checks + 1;
    result := public.nikon_save_preset(fixture_id, 3, clear_draft);
    IF (result->>'revision')::integer IS DISTINCT FROM 3 OR (SELECT count(*) FROM public.nikon_preset_versions WHERE preset_id = fixture_id) <> 3 THEN
      RAISE EXCEPTION 'ASSERT 15: an unchanged category save must remain idempotent';
    END IF;
    checks := checks + 1;
    PERFORM public.nikon_publish_preset(fixture_id, 3, false);
    IF EXISTS (SELECT 1 FROM jsonb_array_elements(public.nikon_catalog_manifest()->'presets') e(value) WHERE e.value->>'id' = fixture_id::text)
      OR NOT EXISTS (SELECT 1 FROM public.nikon_assets WHERE id = file_id AND ever_published) THEN
      RAISE EXCEPTION 'ASSERT 16: unpublish must hide categories but preserve historically published files';
    END IF;
    checks := checks + 1;
    PERFORM public.nikon_publish_preset(fixture_id, 3, true);
    SELECT e.value INTO item FROM jsonb_array_elements(public.nikon_catalog_manifest()->'presets') e(value)
      WHERE e.value->>'id' = fixture_id::text;
    IF item->>'category' IS DISTINCT FROM '' OR (SELECT count(*) FROM public.nikon_preset_versions WHERE preset_id = fixture_id) <> 3 THEN
      RAISE EXCEPTION 'ASSERT 17: republish must restore the last saved category without adding versions';
    END IF;
    checks := checks + 1;
    IF has_function_privilege('anon', 'public.nikon_catalog_manifest()', 'EXECUTE')
      OR has_function_privilege('authenticated', 'public.nikon_catalog_manifest()', 'EXECUTE')
      OR NOT has_function_privilege('service_role', 'public.nikon_catalog_manifest()', 'EXECUTE')
      OR (SELECT prosecdef FROM pg_proc WHERE oid = 'public.nikon_catalog_manifest()'::regprocedure) THEN
      RAISE EXCEPTION 'ASSERT 18: category migration must preserve service-only SECURITY INVOKER permissions';
    END IF;
    checks := checks + 1;
    RAISE EXCEPTION USING ERRCODE = 'NQC01', MESSAGE = 'nikon_category_force_rollback';
  EXCEPTION WHEN SQLSTATE 'NQC01' THEN
    IF SQLERRM <> 'nikon_category_force_rollback' THEN RAISE; END IF;
    rollback_seen := true;
  END;
  IF NOT rollback_seen OR fixture_id IS NULL
    OR EXISTS (SELECT 1 FROM public.nikon_assets WHERE id = file_id)
    OR EXISTS (SELECT 1 FROM public.nikon_presets WHERE id = fixture_id)
    OR EXISTS (SELECT 1 FROM public.nikon_preset_versions WHERE preset_id = fixture_id) THEN
    RAISE EXCEPTION 'ASSERT 19: forced rollback must leave no synthetic rows';
  END IF;
  checks := checks + 1;
  IF EXISTS (SELECT 1 FROM jsonb_array_elements(public.nikon_catalog_manifest()->'presets') e(value) WHERE e.value->>'id' = fixture_id::text) THEN
    RAISE EXCEPTION 'ASSERT 20: rolled-back fixture must not appear in the public catalog';
  END IF;
  checks := checks + 1;
  IF checks <> 20 THEN RAISE EXCEPTION 'Category acceptance assertion count mismatch'; END IF;
  RAISE NOTICE 'PASS: % category database assertions; all fixtures rolled back', checks;
END;
$nikon_category_acceptance$;
