#!/usr/bin/env node

import crypto from 'node:crypto';
import path from 'node:path';
import { mkdir, readFile, stat, writeFile } from 'node:fs/promises';

function parseArgs(argv) {
  const args = {};
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (!arg.startsWith('--')) continue;
    const key = arg.slice(2);
    const next = argv[i + 1];
    if (!next || next.startsWith('--')) {
      args[key] = true;
      continue;
    }
    args[key] = next;
    i += 1;
  }
  return args;
}

function requireArg(args, key) {
  const value = args[key];
  if (!value || value === true) {
    throw new Error(`Missing required argument: --${key}`);
  }
  return String(value);
}

function trimSlash(value) {
  return value.replace(/\/+$/, '');
}

function ensureAbsoluteUrl(value) {
  const trimmed = String(value || '').trim();
  if (/^https?:\/\//i.test(trimmed)) return trimmed;
  if (trimmed.startsWith('//')) return `https:${trimmed}`;
  return `https://${trimmed.replace(/^\/+/, '')}`;
}

function joinUrl(base, suffix) {
  return `${trimSlash(base)}/${String(suffix).replace(/^\/+/, '')}`;
}

function parseInteger(value, name) {
  const parsed = Number.parseInt(String(value), 10);
  if (!Number.isFinite(parsed) || parsed < 0) {
    throw new Error(`${name} must be a non-negative integer`);
  }
  return parsed;
}

function parseBoolean(value, defaultValue = true) {
  if (value === undefined || value === true) return defaultValue;
  const normalized = String(value).trim().toLowerCase();
  if (['1', 'true', 'yes', 'y'].includes(normalized)) return true;
  if (['0', 'false', 'no', 'n'].includes(normalized)) return false;
  return defaultValue;
}

function normalizeRecentCommit(entry) {
  if (!entry || typeof entry !== 'object') return null;
  const id = typeof entry.id === 'string' ? entry.id.trim() : '';
  const shortId = typeof entry.short_id === 'string' ? entry.short_id.trim() : '';
  const title = typeof entry.title === 'string' ? entry.title.trim() : '';
  const publishedAt = typeof entry.published_at === 'string' ? entry.published_at.trim() : '';
  const resolvedId = id || shortId;
  const resolvedShortId = shortId || resolvedId.slice(0, 7);
  if (!resolvedId && !title && !publishedAt) return null;
  return {
    id: resolvedId,
    short_id: resolvedShortId,
    title,
    published_at: publishedAt,
  };
}

function parseRecentCommits(value) {
  if (!value || value === true) return [];
  let parsed;
  try {
    parsed = JSON.parse(String(value));
  } catch (error) {
    throw new Error(`Invalid --recent-commits-json payload: ${error instanceof Error ? error.message : String(error)}`);
  }
  if (!Array.isArray(parsed)) {
    throw new Error('Invalid --recent-commits-json payload: expected a JSON array');
  }
  return parsed.map(normalizeRecentCommit).filter(Boolean).slice(0, 10);
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const apkPath = requireArg(args, 'apk');
  const outputPath = requireArg(args, 'output');
  const version = requireArg(args, 'version');
  const versionCode = parseInteger(requireArg(args, 'version-code'), 'version-code');
  const commit = requireArg(args, 'commit');
  const publishedAt = requireArg(args, 'published-at');
  const baseUrl = trimSlash(ensureAbsoluteUrl(requireArg(args, 'base-url')));
  const packageName = args['package-name'] && args['package-name'] !== true ? String(args['package-name']) : 'com.tx5dr.bridge';
  const tag = args.tag && args.tag !== true ? String(args.tag) : 'nightly-android-bridge';
  const channel = args.channel && args.channel !== true ? String(args.channel) : 'nightly';
  const commitTitle = args['commit-title'] && args['commit-title'] !== true ? String(args['commit-title']).trim() : '';
  const minSdk = parseInteger(args['min-sdk'] || '28', 'min-sdk');
  const targetSdk = parseInteger(args['target-sdk'] || '34', 'target-sdk');
  const signed = parseBoolean(args.signed, true);
  const recentCommits = parseRecentCommits(args['recent-commits-json']);

  const fileName = path.basename(apkPath);
  const [fileBuffer, fileStats] = await Promise.all([readFile(apkPath), stat(apkPath)]);
  const sha256 = crypto.createHash('sha256').update(fileBuffer).digest('hex');
  const assetUrl = joinUrl(baseUrl, fileName);

  const manifest = {
    product: 'android-bridge',
    channel,
    tag,
    version,
    version_code: versionCode,
    package_name: packageName,
    commit,
    commit_short: commit.slice(0, 7),
    commit_title: commitTitle,
    published_at: publishedAt,
    base_url: baseUrl,
    recent_commits: recentCommits,
    assets: [
      {
        name: fileName,
        url: assetUrl,
        url_cn: assetUrl,
        url_oss: assetUrl,
        url_global: assetUrl,
        sha256,
        size: fileStats.size,
        platform: 'android',
        arch: 'arm64',
        package_type: 'apk',
        min_sdk: minSdk,
        target_sdk: targetSdk,
        signed,
      },
    ],
  };

  await mkdir(path.dirname(outputPath), { recursive: true });
  await writeFile(outputPath, `${JSON.stringify(manifest, null, 2)}\n`);
  console.log(`Generated Android bridge manifest: ${outputPath}`);
}

main().catch((error) => {
  console.error(error instanceof Error ? error.message : error);
  process.exitCode = 1;
});
