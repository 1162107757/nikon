-- Expand accepted install IDs without rewriting or republishing existing APK records.
BEGIN;
ALTER TABLE public.nikon_app_releases
  DROP CONSTRAINT IF EXISTS nikon_app_releases_package_name_check;
ALTER TABLE public.nikon_app_releases
  ADD CONSTRAINT nikon_app_releases_package_name_check
  CHECK (package_name IN ('com.nikonpreset.usbprobe', 'cc.huevia'));
COMMIT;
