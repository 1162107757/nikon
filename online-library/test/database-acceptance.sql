-- Explicit live PostgreSQL acceptance only; never included in the default Node test runner.
-- Expected assertions: 24.
-- Only synthetic, transaction-local assets/presets are inserted. No storage object is uploaded.
-- The inner exception subtransaction is deliberately rolled back before this DO can finish.
-- No administrator, credential, session, password hash, real row or schema is read/modified.
DO $nikon_database_acceptance$
DECLARE
  nonce text := replace(gen_random_uuid()::text, '-', '');
  file_hash text := nonce || replace(gen_random_uuid()::text, '-', '');
  next_hash text := nonce || replace(gen_random_uuid()::text, '-', '');
  preview_hash text := nonce || replace(gen_random_uuid()::text, '-', '');
  file_id text := 'ncp-' || file_hash;
  next_id text := 'ncp-' || next_hash;
  preview_id text := 'preview-' || preview_hash;
  fixture_id uuid;
  file_payload jsonb;
  next_payload jsonb;
  preview_payload jsonb;
  first_draft jsonb;
  next_draft jsonb;
  result jsonb;
  catalog_item jsonb;
  expected_error boolean;
  rollback_seen boolean := false;
  checks integer := 0;
BEGIN
  BEGIN
    IF EXISTS (SELECT 1 FROM public.nikon_assets WHERE id IN (file_id, next_id, preview_id)) THEN
      RAISE EXCEPTION 'ASSERT 01: synthetic fixture collision';
    END IF;
    checks := checks + 1;

    file_payload := jsonb_build_object('id', file_id, 'kind', 'ncp', 'sha256', file_hash,
      'byteLength', 52, 'ncpVersion', '0100', 'originalName', 'SYNTHETIC.NCP',
      'storagePath', 'ncp/' || file_hash || '.ncp',
      'url', 'https://acceptance.invalid/files/' || file_id || '.ncp');
    next_payload := jsonb_build_object('id', next_id, 'kind', 'ncp', 'sha256', next_hash,
      'byteLength', 52, 'ncpVersion', '0100', 'originalName', 'SYNTHETIC-NEXT.NCP',
      'storagePath', 'ncp/' || next_hash || '.ncp',
      'url', 'https://acceptance.invalid/files/' || next_id || '.ncp');
    preview_payload := jsonb_build_object('id', preview_id, 'kind', 'preview', 'sha256', preview_hash,
      'byteLength', 69, 'mimeType', 'image/png', 'extension', 'png', 'originalName', 'SYNTHETIC.png',
      'storagePath', 'preview/' || preview_hash || '.png',
      'url', 'https://acceptance.invalid/files/' || preview_id || '.png');
    INSERT INTO public.nikon_assets(id, payload) VALUES
      (file_id, file_payload), (next_id, next_payload), (preview_id, preview_payload);
    IF (SELECT count(*) FROM public.nikon_assets WHERE id IN (file_id, next_id, preview_id) AND NOT ever_published) <> 3 THEN
      RAISE EXCEPTION 'ASSERT 02: new assets must remain private';
    END IF;
    checks := checks + 1;

    first_draft := jsonb_build_object('name', '__acceptance_first_' || nonce, 'description', 'synthetic first snapshot',
      'compatibleModels', jsonb_build_array('Synthetic camera'), 'sortOrder', 0,
      'file', file_payload, 'preview', preview_payload);
    next_draft := first_draft || jsonb_build_object('name', '__acceptance_next_' || nonce,
      'description', 'synthetic unpublished edit', 'file', next_payload);
    result := public.nikon_save_preset(NULL::uuid, NULL::integer, first_draft);
    fixture_id := (result->>'id')::uuid;
    IF fixture_id IS NULL OR result->>'status' IS DISTINCT FROM 'draft'
      OR (result->>'revision')::integer IS DISTINCT FROM 1
      OR result->'draft' IS DISTINCT FROM first_draft OR result->'published' IS DISTINCT FROM 'null'::jsonb THEN
      RAISE EXCEPTION 'ASSERT 03: initial save must only create revision-one draft';
    END IF;
    checks := checks + 1;

    IF (SELECT count(*) FROM public.nikon_preset_versions WHERE preset_id = fixture_id) <> 1
      OR NOT EXISTS (SELECT 1 FROM public.nikon_preset_versions WHERE preset_id = fixture_id AND revision = 1 AND snapshot = first_draft) THEN
      RAISE EXCEPTION 'ASSERT 04: initial draft version must be retained';
    END IF;
    checks := checks + 1;
    IF EXISTS (SELECT 1 FROM jsonb_array_elements(public.nikon_catalog_manifest()->'presets') AS e(value)
      WHERE e.value->>'id' = fixture_id::text) THEN
      RAISE EXCEPTION 'ASSERT 05: draft must not appear in catalog';
    END IF;
    checks := checks + 1;

    expected_error := false;
    BEGIN
      PERFORM public.nikon_publish_preset(fixture_id, 0, true);
    EXCEPTION WHEN raise_exception THEN
      IF SQLERRM <> 'REVISION_CONFLICT' THEN RAISE; END IF;
      expected_error := true;
    END;
    IF NOT expected_error OR EXISTS (SELECT 1 FROM public.nikon_assets WHERE id IN (file_id, next_id, preview_id) AND ever_published)
      OR NOT EXISTS (SELECT 1 FROM public.nikon_presets WHERE id = fixture_id AND status = 'draft' AND revision = 1) THEN
      RAISE EXCEPTION 'ASSERT 06: failed publication must not change draft or asset visibility';
    END IF;
    checks := checks + 1;

    result := public.nikon_publish_preset(fixture_id, 1, true);
    IF result->>'status' IS DISTINCT FROM 'published' OR result->'published' IS DISTINCT FROM first_draft
      OR (result->>'published_revision')::integer IS DISTINCT FROM 1 THEN
      RAISE EXCEPTION 'ASSERT 07: publication must capture the selected draft revision';
    END IF;
    checks := checks + 1;
    IF (SELECT count(*) FROM public.nikon_assets WHERE id IN (file_id, preview_id) AND ever_published) <> 2
      OR NOT EXISTS (SELECT 1 FROM public.nikon_assets WHERE id = next_id AND NOT ever_published) THEN
      RAISE EXCEPTION 'ASSERT 08: only published-snapshot assets must become public in the publication transaction';
    END IF;
    checks := checks + 1;

    SELECT e.value INTO catalog_item FROM jsonb_array_elements(public.nikon_catalog_manifest()->'presets') AS e(value)
      WHERE e.value->>'id' = fixture_id::text;
    IF catalog_item IS NULL OR catalog_item->>'name' IS DISTINCT FROM first_draft->>'name'
      OR catalog_item->>'sha256' IS DISTINCT FROM file_hash OR catalog_item->>'previewUrl' IS DISTINCT FROM preview_payload->>'url'
      OR catalog_item ?| ARRAY['draft', 'published', 'password_hash', 'storagePath', 'sortOrder'] THEN
      RAISE EXCEPTION 'ASSERT 09: public catalog must contain only the published client fields';
    END IF;
    checks := checks + 1;

    result := public.nikon_save_preset(fixture_id, 1, next_draft);
    IF (result->>'revision')::integer IS DISTINCT FROM 2 OR result->'draft' IS DISTINCT FROM next_draft
      OR result->'published' IS DISTINCT FROM first_draft OR (result->>'published_revision')::integer IS DISTINCT FROM 1
      OR result->>'status' IS DISTINCT FROM 'published' THEN
      RAISE EXCEPTION 'ASSERT 10: editing a published preset must leave the published snapshot unchanged';
    END IF;
    checks := checks + 1;
    IF (SELECT count(*) FROM public.nikon_preset_versions WHERE preset_id = fixture_id) <> 2
      OR NOT EXISTS (SELECT 1 FROM public.nikon_preset_versions WHERE preset_id = fixture_id AND revision = 2 AND snapshot = next_draft) THEN
      RAISE EXCEPTION 'ASSERT 11: both saved versions must remain available';
    END IF;
    checks := checks + 1;
    SELECT e.value INTO catalog_item FROM jsonb_array_elements(public.nikon_catalog_manifest()->'presets') AS e(value)
      WHERE e.value->>'id' = fixture_id::text;
    IF catalog_item IS NULL OR catalog_item->>'name' IS DISTINCT FROM first_draft->>'name'
      OR catalog_item->>'sha256' IS DISTINCT FROM file_hash
      OR NOT EXISTS (SELECT 1 FROM public.nikon_assets WHERE id = next_id AND NOT ever_published) THEN
      RAISE EXCEPTION 'ASSERT 12: saved edits and replacement file must stay unpublished';
    END IF;
    checks := checks + 1;

    expected_error := false;
    BEGIN
      PERFORM public.nikon_save_preset(fixture_id, 1, first_draft);
    EXCEPTION WHEN raise_exception THEN
      IF SQLERRM <> 'REVISION_CONFLICT' THEN RAISE; END IF;
      expected_error := true;
    END;
    IF NOT expected_error OR (SELECT count(*) FROM public.nikon_preset_versions WHERE preset_id = fixture_id) <> 2 THEN
      RAISE EXCEPTION 'ASSERT 13: stale save must be rejected without adding versions';
    END IF;
    checks := checks + 1;
    expected_error := false;
    BEGIN
      PERFORM public.nikon_publish_preset(fixture_id, 1, false);
    EXCEPTION WHEN raise_exception THEN
      IF SQLERRM <> 'REVISION_CONFLICT' THEN RAISE; END IF;
      expected_error := true;
    END;
    IF NOT expected_error OR NOT EXISTS (SELECT 1 FROM public.nikon_presets WHERE id = fixture_id AND status = 'published') THEN
      RAISE EXCEPTION 'ASSERT 14: stale unpublish must not remove the live snapshot';
    END IF;
    checks := checks + 1;

    result := public.nikon_publish_preset(fixture_id, 2, false);
    IF result->>'status' IS DISTINCT FROM 'unpublished' OR result->'published' IS DISTINCT FROM first_draft
      OR (result->>'revision')::integer IS DISTINCT FROM 2
      OR (SELECT count(*) FROM public.nikon_preset_versions WHERE preset_id = fixture_id) <> 2 THEN
      RAISE EXCEPTION 'ASSERT 15: unpublish must retain draft, published history and versions';
    END IF;
    checks := checks + 1;
    IF EXISTS (SELECT 1 FROM jsonb_array_elements(public.nikon_catalog_manifest()->'presets') AS e(value) WHERE e.value->>'id' = fixture_id::text)
      OR (SELECT count(*) FROM public.nikon_assets WHERE id IN (file_id, preview_id) AND ever_published) <> 2
      OR NOT EXISTS (SELECT 1 FROM public.nikon_assets WHERE id = next_id AND NOT ever_published) THEN
      RAISE EXCEPTION 'ASSERT 16: unpublish must hide the catalog item and retain historical file visibility';
    END IF;
    checks := checks + 1;

    result := public.nikon_publish_preset(fixture_id, 2, true);
    IF result->'published' IS DISTINCT FROM next_draft OR (result->>'published_revision')::integer IS DISTINCT FROM 2
      OR (SELECT count(*) FROM public.nikon_preset_versions WHERE preset_id = fixture_id) <> 2 THEN
      RAISE EXCEPTION 'ASSERT 17: republish must capture revision two without fabricating a saved version';
    END IF;
    checks := checks + 1;
    IF (SELECT count(*) FROM public.nikon_assets WHERE id IN (file_id, next_id, preview_id) AND ever_published) <> 3 THEN
      RAISE EXCEPTION 'ASSERT 18: republish must make the new file visible and retain older files';
    END IF;
    checks := checks + 1;
    SELECT e.value INTO catalog_item FROM jsonb_array_elements(public.nikon_catalog_manifest()->'presets') AS e(value)
      WHERE e.value->>'id' = fixture_id::text;
    IF catalog_item IS NULL OR catalog_item->>'name' IS DISTINCT FROM next_draft->>'name' OR catalog_item->>'sha256' IS DISTINCT FROM next_hash THEN
      RAISE EXCEPTION 'ASSERT 19: catalog must switch to the explicitly republished snapshot';
    END IF;
    checks := checks + 1;

    result := public.nikon_save_preset(fixture_id, 2, next_draft);
    IF (result->>'revision')::integer IS DISTINCT FROM 2 OR (SELECT count(*) FROM public.nikon_preset_versions WHERE preset_id = fixture_id) <> 2 THEN
      RAISE EXCEPTION 'ASSERT 20: unchanged draft save must be idempotent';
    END IF;
    checks := checks + 1;
    expected_error := false;
    BEGIN
      PERFORM public.nikon_save_preset(fixture_id, 2, jsonb_set(next_draft, '{file,byteLength}', '53'::jsonb));
    EXCEPTION WHEN raise_exception THEN
      IF SQLERRM <> 'INVALID_FILE' THEN RAISE; END IF;
      expected_error := true;
    END;
    IF NOT expected_error OR (SELECT count(*) FROM public.nikon_preset_versions WHERE preset_id = fixture_id) <> 2 THEN
      RAISE EXCEPTION 'ASSERT 21: mismatched file metadata must not create a version';
    END IF;
    checks := checks + 1;
    expected_error := false;
    BEGIN
      PERFORM public.nikon_save_preset(fixture_id, 2, jsonb_set(next_draft, '{preview,kind}', '"ncp"'::jsonb));
    EXCEPTION WHEN raise_exception THEN
      IF SQLERRM <> 'INVALID_PREVIEW' THEN RAISE; END IF;
      expected_error := true;
    END;
    IF NOT expected_error OR (SELECT count(*) FROM public.nikon_preset_versions WHERE preset_id = fixture_id) <> 2 THEN
      RAISE EXCEPTION 'ASSERT 22: mismatched preview metadata must not create a version';
    END IF;
    checks := checks + 1;
    IF NOT EXISTS (SELECT 1 FROM public.nikon_preset_versions WHERE preset_id = fixture_id AND revision = 1 AND snapshot = first_draft)
      OR NOT EXISTS (SELECT 1 FROM public.nikon_preset_versions WHERE preset_id = fixture_id AND revision = 2 AND snapshot = next_draft)
      OR NOT EXISTS (SELECT 1 FROM public.nikon_presets WHERE id = fixture_id AND revision = 2 AND published = next_draft AND draft = next_draft)
      OR NOT EXISTS (SELECT 1 FROM public.nikon_assets WHERE id = file_id AND payload = file_payload) THEN
      RAISE EXCEPTION 'ASSERT 23: saved histories and original immutable asset metadata must survive all operations';
    END IF;
    checks := checks + 1;

    -- Catching exactly this marker rolls back every fixture and function effect above.
    RAISE EXCEPTION USING ERRCODE = 'NQA01', MESSAGE = 'nikon_acceptance_force_rollback';
  EXCEPTION WHEN SQLSTATE 'NQA01' THEN
    IF SQLERRM <> 'nikon_acceptance_force_rollback' THEN RAISE; END IF;
    rollback_seen := true;
  END;

  IF NOT rollback_seen OR fixture_id IS NULL
    OR EXISTS (SELECT 1 FROM public.nikon_assets WHERE id IN (file_id, next_id, preview_id))
    OR EXISTS (SELECT 1 FROM public.nikon_presets WHERE id = fixture_id)
    OR EXISTS (SELECT 1 FROM public.nikon_preset_versions WHERE preset_id = fixture_id)
    OR EXISTS (SELECT 1 FROM jsonb_array_elements(public.nikon_catalog_manifest()->'presets') AS e(value) WHERE e.value->>'id' = fixture_id::text) THEN
    RAISE EXCEPTION 'ASSERT 24: forced rollback must leave no fixture or catalog entry';
  END IF;
  checks := checks + 1;
  IF checks <> 24 THEN RAISE EXCEPTION 'Acceptance assertion count mismatch'; END IF;
  RAISE NOTICE 'PASS: % database acceptance assertions; all fixtures rolled back', checks;
END;
$nikon_database_acceptance$;
