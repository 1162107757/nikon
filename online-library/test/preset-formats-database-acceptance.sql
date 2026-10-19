BEGIN;
SET LOCAL ROLE service_role;
DO $test$
DECLARE actor uuid:=gen_random_uuid(); curated uuid; community uuid; r jsonb; item jsonb; file jsonb; legacy jsonb; bad jsonb; error_caught boolean;
BEGIN
 INSERT INTO nikon_users(id,nickname) VALUES(actor,'format-fixture');
 file:=jsonb_build_object('id','ncp-'||repeat('f',64),'kind','ncp','sha256',repeat('f',64),'byteLength',392,
  'ncpVersion','0310','fileFormat','NP3','extension','np3','storagePath','ncp/'||repeat('f',64)||'.np3',
  'url','https://fixture.invalid/files/ncp-'||repeat('f',64)||'.np3','originalName','fixture.NP3');
 legacy:=jsonb_build_object('id','ncp-'||repeat('a',64),'kind','ncp','sha256',repeat('a',64),'byteLength',52,
  'ncpVersion','0100','storagePath','ncp/'||repeat('a',64)||'.ncp',
  'url','https://fixture.invalid/files/ncp-'||repeat('a',64)||'.ncp','originalName','legacy.NCP');
 IF NOT nikon_preset_metadata_valid(file) OR NOT nikon_preset_metadata_valid(legacy) THEN RAISE EXCEPTION 'ASSERT valid NP3 and legacy NCP'; END IF;
 PERFORM nikon_community('addAsset',actor,NULL,jsonb_build_object('asset',file));
 PERFORM nikon_community('addAsset',actor,NULL,jsonb_build_object('asset',legacy));
 FOREACH bad IN ARRAY ARRAY[file||'{"ncpVersion":"0300"}'::jsonb,file||'{"fileFormat":"NCP"}'::jsonb,
  file||'{"extension":"ncp"}'::jsonb,file-'fileFormat',file-'ncpVersion',file-'extension',legacy||'{"fileFormat":"UNKNOWN"}'::jsonb] LOOP
  error_caught:=false;
  BEGIN PERFORM nikon_community('addAsset',actor,NULL,jsonb_build_object('asset',bad));
  EXCEPTION WHEN OTHERS THEN IF SQLERRM='INVALID_FILE' THEN error_caught:=true; ELSE RAISE; END IF; END;
  IF NOT error_caught THEN RAISE EXCEPTION 'ASSERT invalid preset format/version pair accepted'; END IF;
 END LOOP;
 r:=nikon_community('save',actor,NULL,jsonb_build_object('name','NP3 community fixture','rightsConfirmed',true,'fileAssetId',file->>'id','submit',true,'idempotencyKey','format-submission-001'));
 community:=(r->>'id')::uuid;
 IF r->>'fileFormat' IS DISTINCT FROM 'NP3' THEN RAISE EXCEPTION 'ASSERT own detail format'; END IF;
 PERFORM nikon_community('adminReviewSubmission',NULL,community,'{"expectedRevision":1,"decision":"approve","actor":"fixture"}');
 r:=nikon_community('detail',NULL,community);
 IF r->>'fileFormat' IS DISTINCT FROM 'NP3' OR r->>'fileUrl' NOT LIKE '%.np3' THEN RAISE EXCEPTION 'ASSERT community public detail format'; END IF;
 INSERT INTO nikon_assets(id,payload) VALUES(file->>'id',file),(legacy->>'id',legacy);
 r:=nikon_save_preset(NULL,NULL,jsonb_build_object('name','NP3 curated fixture','file',file,'preview',NULL)); curated:=(r->>'id')::uuid;
 PERFORM nikon_publish_preset(curated,1,true);
 SELECT value INTO item FROM jsonb_array_elements(nikon_catalog_manifest()->'presets') WHERE value->>'id'=curated::text;
 IF item->>'fileFormat' IS DISTINCT FROM 'NP3' OR item->>'fileUrl' NOT LIKE '%.np3' THEN RAISE EXCEPTION 'ASSERT curated catalog NP3'; END IF;
 r:=nikon_community('detail',NULL,curated);
 IF r->>'fileFormat' IS DISTINCT FROM 'NP3' THEN RAISE EXCEPTION 'ASSERT curated detail NP3'; END IF;
 r:=nikon_save_preset(NULL,NULL,jsonb_build_object('name','NCP legacy fixture','file',legacy,'preview',NULL)); curated:=(r->>'id')::uuid;
 PERFORM nikon_publish_preset(curated,1,true);
 SELECT value INTO item FROM jsonb_array_elements(nikon_catalog_manifest()->'presets') WHERE value->>'id'=curated::text;
 IF item->>'fileFormat' IS DISTINCT FROM 'NCP' THEN RAISE EXCEPTION 'ASSERT legacy catalog default NCP'; END IF;
 r:=nikon_community('detail',NULL,curated);
 IF r->>'fileFormat' IS DISTINCT FROM 'NCP' THEN RAISE EXCEPTION 'ASSERT legacy feed default NCP'; END IF;
 IF has_function_privilege('anon','public.nikon_preset_metadata_valid(jsonb)','EXECUTE')
  OR has_function_privilege('authenticated','public.nikon_preset_metadata_valid(jsonb)','EXECUTE') THEN RAISE EXCEPTION 'ASSERT metadata helper service only'; END IF;
END;
$test$;
ROLLBACK;
