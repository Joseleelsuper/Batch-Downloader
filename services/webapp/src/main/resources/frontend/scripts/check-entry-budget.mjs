import { readFileSync } from 'node:fs';
import { gzipSync } from 'node:zlib';

const budgetBytes = 112 * 1024;
const manifest = JSON.parse(readFileSync('dist/.vite/manifest.json', 'utf8'));
const entry = Object.values(manifest).find((item) => item.isEntry);

if (!entry?.file) {
  throw new Error('frontend_entry_manifest_missing');
}

const compressedBytes = gzipSync(readFileSync(`dist/${entry.file}`)).byteLength;
const compressedKilobytes = compressedBytes / 1024;

if (compressedBytes > budgetBytes) {
  throw new Error(
    `frontend_entry_budget_exceeded:${compressedKilobytes.toFixed(2)}KiB>112KiB`,
  );
}

process.stdout.write(
  `Entrada pública: ${compressedKilobytes.toFixed(2)} KiB gzip (límite 112 KiB).\n`,
);
