import { fileURLToPath } from 'node:url';
import { Parser, fromFile } from '@asyncapi/parser';

const filename = process.argv[2] ?? fileURLToPath(
  new URL('../../shared/contracts/asyncapi/messaging.yaml', import.meta.url),
);
const { document, diagnostics } = await fromFile(new Parser(), filename).parse();

for (const diagnostic of diagnostics) {
  console.error(`${diagnostic.code} ${diagnostic.path.join('.')}: ${diagnostic.message}`);
}

if (!document || diagnostics.some(({ severity }) => severity === 0)) {
  process.exitCode = 1;
} else {
  console.log(`AsyncAPI válido: ${filename}`);
}
