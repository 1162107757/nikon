BEGIN;
CREATE OR REPLACE FUNCTION pg_temp.check_true(v boolean,label text) RETURNS void LANGUAGE plpgsql AS $$ BEGIN IF v IS DISTINCT FROM true THEN RAISE EXCEPTION 'FAIL: %',label; END IF;END $$;
DO $$
DECLARE a uuid:=gen_random_uuid();b uuid:=gen_random_uuid();h text:=repeat('a',64);m jsonb:='{"displayName":"Private","originalFileName":"x.NCP","category":"风景","description":"","byteLength":52,"fileFormat":"NCP","ncpVersion":"0100"}';r jsonb;caught boolean;
BEGIN
 INSERT INTO nikon_users(id,nickname)VALUES(a,'A'),(b,'B');
 PERFORM nikon_personal_library('reserve',a,h,'{"byteLength":52}');
 r:=nikon_personal_library('put',a,h,jsonb_build_object('expectedRevision',0,'metadata',m));PERFORM pg_temp.check_true(r->>'revision'='1','first save');
 r:=nikon_personal_library('put',a,h,jsonb_build_object('expectedRevision',0,'metadata',m));PERFORM pg_temp.check_true(r->>'revision'='1','lost reply exact replay');
 r:=nikon_personal_library('list',b);PERFORM pg_temp.check_true(jsonb_array_length(r->'items')=0,'other account cannot list');
 caught:=false;BEGIN PERFORM nikon_personal_library('get',b,h);EXCEPTION WHEN raise_exception THEN caught:=SQLERRM='NOT_FOUND';END;PERFORM pg_temp.check_true(caught,'other account cannot read');
 PERFORM nikon_personal_library('delete',b,h,'{"expectedRevision":0}');PERFORM pg_temp.check_true((SELECT NOT deleted FROM nikon_personal_presets WHERE user_id=a AND sha256=h),'other account cannot delete');
 caught:=false;BEGIN PERFORM nikon_personal_library('put',a,h,jsonb_build_object('expectedRevision',0,'metadata',m||'{"category":"人像"}'));EXCEPTION WHEN raise_exception THEN caught:=SQLERRM='REVISION_CONFLICT';END;PERFORM pg_temp.check_true(caught,'concurrent metadata conflict');
 PERFORM nikon_personal_library('delete',a,h,'{"expectedRevision":1}');
 r:=nikon_personal_library('list',a);PERFORM pg_temp.check_true((r->'items'->0->>'deleted')::boolean AND r->'items'->0->>'revision'='2','deletion tombstone retained');
 caught:=false;BEGIN PERFORM nikon_personal_library('get',a,h);EXCEPTION WHEN raise_exception THEN caught:=SQLERRM='NOT_FOUND';END;PERFORM pg_temp.check_true(caught,'deleted file unavailable');
 PERFORM nikon_personal_library('put',a,h,jsonb_build_object('expectedRevision',2,'metadata',m));
 INSERT INTO nikon_personal_uploads(user_id)SELECT a FROM generate_series(1,120);
 caught:=false;BEGIN PERFORM nikon_personal_library('reserve',a,h,'{"byteLength":52}');EXCEPTION WHEN raise_exception THEN caught:=SQLERRM='UPLOAD_QUOTA';END;PERFORM pg_temp.check_true(caught,'per account budget');
 PERFORM pg_temp.check_true(NOT has_table_privilege('anon','nikon_personal_presets','SELECT')AND NOT has_function_privilege('authenticated','nikon_personal_library(text,uuid,text,jsonb)','EXECUTE'),'service-only tables and RPC');
END $$;
ROLLBACK;
