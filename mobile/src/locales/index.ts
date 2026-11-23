import { en } from './en';
// Add complete fr/ar dictionaries here before enabling those languages.
// Keep keys stable; layout uses start/end spacing for future RTL support.
export type MessageKey = keyof typeof en;
export const t = (key: MessageKey): string => en[key];
// Message with {name} placeholders, e.g. tf('noTransactionsIn', { currency: 'EUR' }).
export const tf = (key: MessageKey, values: Record<string, string | number>): string =>
  t(key).replace(/\{(\w+)\}/g, (match, name: string) =>
    name in values ? String(values[name]) : match,
  );
// BCP 47 locale used for number, money and date formatting (matches the active dictionary).
export const locale = 'en';
