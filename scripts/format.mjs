import { run as clangFormat } from '@cpp-linter/clang-format';
import { spawnSync } from 'node:child_process';
import {
  globSync,
  mkdtempSync,
  readFileSync,
  rmdirSync,
  rmSync,
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const mode = process.argv[2];
if (process.argv.length !== 3 || !['--write', '--check'].includes(mode)) {
  throw new Error('Use npm run format or npm run format:check');
}

process.chdir(fileURLToPath(new URL('..', import.meta.url)));

function files(patterns) {
  return globSync(patterns, {
    exclude: ['**/build/**', '**/node_modules/**', '**/tmp_*', '**/tmp-*'],
  }).sort();
}

const javaSources = files([
  'src/**/*.java',
  'world-mirror-format/src/**/*.java',
]);
const prettierSources = files([
  'src/**/*.json',
  '.github/workflows/*.yml',
  '.github/workflows/*.yaml',
  '*.md',
  'docs/**/*.md',
  'world-mirror-format/*.md',
  'package*.json',
  '.prettierrc.json',
  'scripts/*.mjs',
]).filter((file) => !file.endsWith('CHANGELOG.md'));
const textSources = files([
  '*.gradle',
  'gradle/*.gradle',
  'gradle.properties',
  'world-mirror-format/*.gradle',
  'scripts/*.ps1',
  '.clang-format',
  '.gitattributes',
  '.gitignore',
]);

const temporary = mkdtempSync(join(tmpdir(), 'world-mirror-format-'));
const list = join(temporary, 'java.txt');
try {
  writeFileSync(list, javaSources.join('\n'));
  const clangArgs = [
    '--style=file:.clang-format',
    '--Werror',
    '--ferror-limit=8',
    `--files=${list}`,
  ];
  const clangResult = clangFormat([
    ...clangArgs,
    mode === '--write' ? '-i' : '--dry-run',
  ]);

  const prettier = spawnSync(
    process.execPath,
    ['node_modules/prettier/bin/prettier.cjs', mode, ...prettierSources],
    { stdio: 'inherit' },
  );
  if (prettier.error) throw prettier.error;

  let textChanged = false;
  // ClangFormat can leave mixed line endings when editing a CRLF file in place.
  for (const file of [...javaSources, ...textSources]) {
    const original = readFileSync(file, 'utf8');
    const normalized = original.replace(/\r\n/g, '\n');
    const formatted = file.endsWith('.java')
      ? normalized
      : normalized.replace(/[ \t]+$/gm, '').replace(/\n*$/, '\n');
    if (formatted === original) continue;
    textChanged = true;
    if (mode === '--write') writeFileSync(file, formatted);
    else console.error(`Formatting required: ${file}`);
  }
  if (
    clangResult !== 0 ||
    prettier.status !== 0 ||
    (mode === '--check' && textChanged)
  ) {
    process.exitCode = 1;
  }
} finally {
  rmSync(list, { force: true });
  rmdirSync(temporary);
}
