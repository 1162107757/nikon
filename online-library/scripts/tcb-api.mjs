// JSON arrives over stdin: large code archives and sensitive values never become OS command-line arguments.
import { readFileSync, existsSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { createRequire } from 'node:module';
const entry = process.env.CLOUDBASE_CLI_ENTRY || resolve(dirname(process.execPath), 'node_modules/@cloudbase/cli/dist/standalone/cli.js');
if (!existsSync(entry)) throw new Error('找不到已安装的 CloudBase CLI，请设置 CLOUDBASE_CLI_ENTRY。');
const [service, action] = process.argv.slice(2);
const body = JSON.parse(readFileSync(0, 'utf8'));
if (!['tcb', 'scf'].includes(service) || !/^[A-Z][a-zA-Z0-9]+$/.test(action || '')) throw new Error('Unsupported cloud API');
const version = service === 'scf' ? '2018-04-16' : '2018-06-08';
process.argv = [process.execPath, entry, 'api', service, action, '-e', 'nikon-d8girdjsr252fd90e', '-r', 'ap-shanghai', '--api-version', version, '--body', JSON.stringify(body), '--json'];
createRequire(import.meta.url)(entry);
