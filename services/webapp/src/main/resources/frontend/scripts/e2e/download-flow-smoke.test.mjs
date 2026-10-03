// @vitest-environment node
import { afterEach, expect, test, vi } from 'vitest';
import { execFileSync, spawnSync } from 'node:child_process';
import { existsSync, mkdtempSync } from 'node:fs';
import { chromium } from 'playwright';
import { run } from './download-flow-smoke.mjs';

vi.mock('node:child_process', () => ({ execFileSync: vi.fn(), spawnSync: vi.fn() }));
vi.mock('playwright', () => ({ chromium: { launch: vi.fn() } }));
vi.mock('node:fs', async (importOriginal) => {
  const fs = await importOriginal();
  return { ...fs, mkdtempSync: vi.fn(fs.mkdtempSync) };
});

afterEach(() => vi.restoreAllMocks());

test('preserves the scenario failure while closing the browser and removing test resources', async () => {
  const scenarioError = new Error('The image build failed');
  const close = vi.fn().mockRejectedValue(new Error('Browser close failed'));
  chromium.launch.mockResolvedValue({ close });
  execFileSync.mockImplementationOnce(() => { throw scenarioError; }).mockReturnValue('service logs');
  spawnSync.mockReturnValue({ status: 1, stderr: 'Compose cleanup failed' });
  vi.spyOn(process.stderr, 'write').mockReturnValue(true);
  vi.spyOn(process.stdout, 'write').mockReturnValue(true);

  await expect(run()).rejects.toBe(scenarioError);

  expect(close).toHaveBeenCalledOnce();
  expect(spawnSync).toHaveBeenCalledWith(expect.any(String),
    expect.arrayContaining(['down', '--volumes', '--remove-orphans']), expect.any(Object));
  expect(existsSync(mkdtempSync.mock.results[0].value)).toBe(false);
});
