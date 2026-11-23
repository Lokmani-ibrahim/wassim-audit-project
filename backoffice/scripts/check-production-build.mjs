import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { resolve, dirname, extname, relative } from 'node:path';
import ts from 'typescript';

const sourceRoot = resolve(import.meta.dirname, '../src');
const seen = new Set();
function checkImports(file) {
  if (seen.has(file)) return;
  seen.add(file);
  const name = relative(sourceRoot, file).replaceAll('\\', '/');
  if (name.startsWith('demo/') || name.startsWith('data/') || name.startsWith('services/')) {
    throw new Error(`Demo module reachable from production: ${name}`);
  }
  if (extname(file) === '.css') return;
  const tree = ts.createSourceFile(file, readFileSync(file, 'utf8'), ts.ScriptTarget.Latest, true);
  const visit = node => {
    const module = (ts.isImportDeclaration(node) || ts.isExportDeclaration(node))
      ? node.moduleSpecifier : ts.isCallExpression(node) && node.expression.kind === ts.SyntaxKind.ImportKeyword
        ? node.arguments[0] : undefined;
    if (module && ts.isStringLiteral(module)) {
      const specifier = module.text;
      if (specifier === 'react-router-dom') throw new Error('Demo router imported by production');
      if (specifier.startsWith('.')) {
        const base = resolve(dirname(file), specifier);
        const target = [base, `${base}.ts`, `${base}.tsx`, `${base}.css`, resolve(base, 'index.ts'),
          resolve(base, 'index.tsx')].find(existsSync);
        if (!target) throw new Error(`Unresolved production import: ${specifier} in ${name}`);
        checkImports(target);
      }
    }
    ts.forEachChild(node, visit);
  };
  visit(tree);
}
checkImports(resolve(sourceRoot, 'main.tsx'));

const root = new URL('../dist/', import.meta.url);
const html = readFileSync(new URL('index.html', root), 'utf8');
if (readdirSync(root).includes('demo.html') ||
    (!html.includes('/src/main.tsx') && !/assets\/index-[^"']+\.js/.test(html)) ||
    html.includes('/src/demo/')) {
  throw new Error('Demo entry leaked into production build');
}
const assets = readdirSync(new URL('assets/', root));
const forbidden = ['Sandbox sign-in', 'This action is simulated', 'example.com', 'seedUsers',
  'listKycCases', 'justifyUnmatchedItem', 'neos-sandbox-auth', 'react-router'];
for (const asset of assets) {
  if (!/\.(js|css)$/.test(asset)) continue;
  const body = readFileSync(new URL(`assets/${asset}`, root), 'utf8');
  for (const marker of forbidden) {
    if (body.includes(marker)) throw new Error(`Demo marker ${marker} leaked into ${asset}`);
  }
}
console.log(`Production build excludes demo entry and fixture markers (${assets.length} assets checked)`);
