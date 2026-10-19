import { mkdir, readdir, writeFile } from 'node:fs/promises';
import { spawnSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

// The parent workflow supplies only public fixture identifiers and hashes, never credentials.
export async function verifyAndroidCatalog({ catalogUrl, presetId, expectedSha256, expectedPreviewSha256 }) {
  const endpoint = new URL(catalogUrl);
  if (endpoint.protocol !== 'https:' || endpoint.username || endpoint.password || endpoint.search || endpoint.hash)
    throw new Error('Native probe requires a stable public HTTPS catalog URL.');
  if (!/^[a-f0-9-]{36}$/.test(presetId) || ![expectedSha256, expectedPreviewSha256].every(value => /^[a-f0-9]{64}$/.test(value)))
    throw new Error('Native probe requires its owned fixture ID and exact hashes.');
  const projectRoot = fileURLToPath(new URL('../../', import.meta.url));
  const jdkHome = process.env.NIKON_VERIFY_JDK || 'D:/dev/Java/jdk-17.0.19';
  const androidJar = process.env.NIKON_VERIFY_ANDROID_JAR || 'D:/dev/android-sdk/platforms/android-35/android.jar';
  const binSuffix = process.platform === 'win32' ? '.exe' : '';
  const scratch = fileURLToPath(new URL(`../build/java-live/${randomUUID()}/`, import.meta.url));
  const classes = path.join(scratch, 'classes');
  await mkdir(classes, { recursive: true });
  const libraryRoot = path.join(projectRoot, 'app/src/main/java/com/nikonpreset/usbprobe/library');
  const sources = (await readdir(libraryRoot)).filter(name => name.endsWith('.java')).map(name => path.join(libraryRoot, name));
  sources.push(path.join(projectRoot, 'app/src/main/java/com/nikonpreset/usbprobe/ncp/NcpFile.java'),
    fileURLToPath(new URL('../test/java/com/nikonpreset/usbprobe/library/OnlineCatalogLiveProbe.java', import.meta.url)));
  function run(executable, args, label, timeout) {
    const result = spawnSync(path.join(jdkHome, 'bin', executable + binSuffix), args,
      { cwd: projectRoot, encoding: 'utf8', windowsHide: true, timeout, maxBuffer: 1024 * 1024 });
    if (result.error || result.status !== 0) {
      // No credentials enter this process. Keep diagnostics limited to compiler/probe output.
      const diagnostic = String(result.stderr || result.stdout || result.error?.code || '').slice(0, 4000);
      throw new Error(`${label} failed: ${diagnostic}`);
    }
    return result.stdout;
  }
  run('javac', ['--release', '17', '-encoding', 'UTF-8', '-cp', androidJar, '-d', classes, ...sources], 'Native library compilation', 60000);
  let output;
  try {
    output = run('java', ['-cp', classes + path.delimiter + androidJar,
      'com.nikonpreset.usbprobe.library.OnlineCatalogLiveProbe', catalogUrl, presetId, expectedSha256,
      expectedPreviewSha256, path.join(scratch, 'app-files')], 'Native public download probe', 150000);
  } catch (error) {
    // Java sees only the public synthetic fixture; retain bounded diagnostics outside the runtime archive.
    await writeFile(path.join(scratch, 'probe-error.txt'), String(error.message).slice(0, 4500) + '\n');
    throw error;
  }
  const match = output.match(/Native public catalog probe passed: (\d+) checks/);
  if (!match) throw new Error('Native probe did not report successful completion.');
  return { checks: Number(match[1]), runtime: 'host JDK 17 with production Android Java sources', imageDecoding: 'not exercised' };
}
