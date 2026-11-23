const { defineConfig } = require('eslint/config');
const expo = require('eslint-config-expo/flat');

const MOCKS_MESSAGE =
  'src/mocks/** may only be loaded from src/services/index.ts (inside the __DEV__ guard) so it is stripped from release bundles.';
const DEV_MESSAGE =
  'src/dev/** (dev-only tools) may only be loaded with a __DEV__-guarded require() so it is stripped from release bundles; check:release-bundle scans for __NEOS_DEV_ONLY__ markers.';
const SUMSUB_MESSAGE =
  'The Sumsub SDK may only be used from src/services/sumsub.ts (token handling, FLAG_SECURE, errorMsg and logging rules live there).';
const MOCKS_PATTERN = { group: ['**/mocks', '**/mocks/**'], message: MOCKS_MESSAGE };

module.exports = defineConfig([
  expo,
  { ignores: ['android/**', 'ios/**', 'dist/**', 'artifacts/**'] },
  {
    rules: {
      'no-restricted-imports': [
        'error',
        {
          paths: [{ name: '@sumsub/react-native-mobilesdk-module', message: SUMSUB_MESSAGE }],
          patterns: [MOCKS_PATTERN, { group: ['**/dev', '**/dev/**'], message: DEV_MESSAGE }],
        },
      ],
      // require() and import() are not covered by no-restricted-imports.
      'no-restricted-syntax': [
        'error',
        {
          selector:
            "CallExpression[callee.name='require'][arguments.0.value=/(^|\\/)mocks(\\/|$)/]",
          message: MOCKS_MESSAGE,
        },
        {
          selector: 'ImportExpression[source.value=/(^|\\/)mocks(\\/|$)/]',
          message: MOCKS_MESSAGE,
        },
        {
          // A legitimate __DEV__-guarded load must carry an explicit eslint-disable.
          selector: "CallExpression[callee.name='require'][arguments.0.value=/(^|\\/)dev(\\/|$)/]",
          message: DEV_MESSAGE,
        },
        {
          selector: 'ImportExpression[source.value=/(^|\\/)dev(\\/|$)/]',
          message: DEV_MESSAGE,
        },
        {
          selector:
            "CallExpression[callee.name='require'][arguments.0.value='@sumsub/react-native-mobilesdk-module']",
          message: SUMSUB_MESSAGE,
        },
        {
          // Prettier turns a stray trailing space after a JSX element into {' '}; as a direct
          // child of a non-Text container React Native throws "Text strings must be rendered
          // within a <Text> component".
          selector:
            'JSXElement[openingElement.name.name=/^(Screen|View|ScrollView|Pressable|Sheet|SafeAreaView|Animated\\.View)$/] > JSXExpressionContainer > Literal[value=/^\\s*$/]',
          message:
            'Whitespace string as a direct child of a non-Text component crashes React Native.',
        },
      ],
    },
  },
  {
    // The only legitimate consumers of the mocks tree.
    files: ['src/services/index.ts', 'src/mocks/**', 'tests/**'],
    rules: { 'no-restricted-imports': 'off', 'no-restricted-syntax': 'off' },
  },
  {
    // The only module that talks to the Sumsub SDK (it loads it lazily with require()).
    files: ['src/services/sumsub.ts'],
    rules: {
      'no-restricted-imports': ['error', { patterns: [MOCKS_PATTERN] }],
      'no-restricted-syntax': 'off',
    },
  },
]);
