// Informational: what would show "not available yet" in a build with no mocks. Always exits 0.
import { mockRegistry, notImplementedActions, sandboxOnlyActions } from '../src/mocks/registry';

const missing = mockRegistry.filter((entry) => !entry.realAvailable);
const ready = mockRegistry.filter((entry) => entry.realAvailable);

console.log(
  'NEOS release readiness (features that would be unavailable with EXPO_PUBLIC_MOCK_FEATURES=none)\n',
);
if (missing.length === 0) console.log('  none: every mockable feature has a real implementation.');
for (const entry of missing) {
  console.log(`  [not available] ${entry.feature}`);
  console.log(`      faked today : ${entry.fakes}`);
  console.log(`      replaced by : ${entry.replacedBy}`);
}
console.log(
  `\nReal implementation exists for: ${ready.map((entry) => entry.feature).join(', ') || 'none'}`,
);
console.log(
  `Not available yet (honest placeholder screens, no service call, not mocks): ${(notImplementedActions as readonly string[]).join(', ') || 'none'}`,
);
console.log('Sandbox only (placeholder in a production build):');
for (const action of sandboxOnlyActions) console.log(`  ${action}`);
console.log(
  `\n${missing.length} of ${mockRegistry.length} mockable features have no real implementation yet.`,
);
