// Sumsub SDK spike (phase 1): the Maven config plugin transform and the sdk-token parser.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { ContractError, parseKycSdkToken } from '../src/api/parse';

const requireCjs = createRequire(__filename);
type MetaData = { $: Record<string, string> };
type Manifest = {
  manifest: { $?: Record<string, string>; application?: { 'meta-data'?: MetaData[] }[] };
};
const plugin = requireCjs('../plugins/withSumsub') as {
  addSumsubMaven(contents: string): string;
  addSecureSumsubWindows(contents: string): string;
  mergeMlKitDependencies(manifest: Manifest): Manifest;
  SUMSUB_MAVEN_URL: string;
  MLKIT_DEPENDENCIES: string;
};

function mlKitEntries(manifest: Manifest): MetaData[] {
  return (manifest.manifest.application?.[0]?.['meta-data'] ?? []).filter(
    (item) => item.$['android:name'] === plugin.MLKIT_DEPENDENCIES,
  );
}

test('withSumsub declares the ML Kit model union with tools:replace (manifest merger conflict)', () => {
  const manifest: Manifest = { manifest: { $: {}, application: [{}] } };
  plugin.mergeMlKitDependencies(manifest);
  const entries = mlKitEntries(manifest);
  assert.equal(entries.length, 1);
  assert.equal(entries[0]?.$['android:value'], 'barcode_ui,face');
  assert.equal(entries[0]?.$['tools:replace'], 'android:value');
  assert.equal(manifest.manifest.$?.['xmlns:tools'], 'http://schemas.android.com/tools');
  // Idempotent, and keeps any model another plugin already declared.
  manifest.manifest.application![0]!['meta-data']![0]!.$['android:value'] = 'ocr';
  plugin.mergeMlKitDependencies(manifest);
  plugin.mergeMlKitDependencies(manifest);
  assert.equal(mlKitEntries(manifest).length, 1);
  assert.equal(mlKitEntries(manifest)[0]?.$['android:value'], 'barcode_ui,face,ocr');
});

// The shape Expo SDK 55 prebuild generates for the root android/build.gradle.
const ROOT_GRADLE = `buildscript {
  repositories {
    google()
    mavenCentral()
  }
}

allprojects {
  repositories {
    google()
    mavenCentral()
    maven { url 'https://www.jitpack.io' }
  }
}

apply plugin: "expo-root-project"
`;

test('withSumsub adds the Sumsub Maven repository to allprojects only', () => {
  const next = plugin.addSumsubMaven(ROOT_GRADLE);
  assert.equal(next.split(plugin.SUMSUB_MAVEN_URL).length - 1, 1);
  const allprojects = next.slice(next.indexOf('allprojects'));
  assert.ok(allprojects.includes(plugin.SUMSUB_MAVEN_URL), 'inside allprojects');
  const buildscript = next.slice(0, next.indexOf('allprojects'));
  assert.ok(!buildscript.includes(plugin.SUMSUB_MAVEN_URL), 'not in buildscript');
  assert.equal(plugin.SUMSUB_MAVEN_URL, 'https://maven.sumsub.com/repository/maven-public/');
});

// The shape Expo SDK 55 prebuild generates for MainApplication.kt (relevant part).
const MAIN_APPLICATION = `package com.neos.mobile

import android.app.Application
import android.content.res.Configuration

class MainApplication : Application(), ReactApplication {
  override fun onCreate() {
    super.onCreate()
    loadReactNative(this)
  }
}
`;

test('withSumsub sets FLAG_SECURE on com.sumsub.* activities from MainApplication.onCreate', () => {
  const next = plugin.addSecureSumsubWindows(MAIN_APPLICATION);
  const after = next.slice(next.indexOf('super.onCreate()'));
  assert.ok(after.indexOf('registerActivityLifecycleCallbacks') < after.indexOf('loadReactNative'));
  assert.match(next, /activity\.javaClass\.name\.startsWith\("com\.sumsub\."\)/);
  assert.match(next, /WindowManager\.LayoutParams\.FLAG_SECURE/);
  assert.match(next, /override fun onActivityCreated/);
  for (const name of ['android.app.Activity', 'android.os.Bundle', 'android.view.WindowManager'])
    assert.equal(next.split(`import ${name}\n`).length - 1, 1, name);
  assert.equal(next.split('import android.app.Application\n').length - 1, 1, 'no duplicate import');
  assert.equal(plugin.addSecureSumsubWindows(next), next, 'idempotent');
  assert.throws(() => plugin.addSecureSumsubWindows('class X {}'), /super\.onCreate/);
});

test('withSumsub is idempotent (prebuild without --clean runs it again)', () => {
  const once = plugin.addSumsubMaven(ROOT_GRADLE);
  assert.equal(plugin.addSumsubMaven(once), once);
});

test('withSumsub fails loudly when the anchor is missing', () => {
  assert.throws(() => plugin.addSumsubMaven('buildscript {\n}\n'), /allprojects/);
});

const TOKEN = '_act-sbx-test-token-value-should-never-leak';

test('parseKycSdkToken accepts the documented shape and keeps the server TTL', () => {
  assert.deepEqual(parseKycSdkToken({ token: TOKEN, expiresInSeconds: 120 }), {
    token: TOKEN,
    expiresInSeconds: 120,
  });
});

test('parseKycSdkToken rejects malformed bodies without echoing the token', () => {
  const bad: unknown[] = [
    null,
    [],
    { expiresInSeconds: 600 },
    { token: '', expiresInSeconds: 600 },
    { token: '   ', expiresInSeconds: 600 },
    { token: 42, expiresInSeconds: 600 },
    { token: TOKEN },
    { token: TOKEN, expiresInSeconds: 0 },
    { token: TOKEN, expiresInSeconds: -5 },
    { token: TOKEN, expiresInSeconds: 1.5 },
    { token: TOKEN, expiresInSeconds: '600' },
  ];
  for (const body of bad) {
    try {
      parseKycSdkToken(body);
      assert.fail(`accepted ${typeof body}`);
    } catch (error) {
      assert.ok(error instanceof ContractError);
      assert.ok(!error.detail.includes(TOKEN), 'detail must not contain the token');
      assert.ok(!error.message.includes(TOKEN), 'message must not contain the token');
    }
  }
});
