import type { TextStyle } from 'react-native';

// Text styles (docs/product/DESIGN_DIRECTION.md §2.2). Three weights; amounts use tabular figures.
const text = {
  display: {
    fontSize: 36,
    lineHeight: 44,
    fontWeight: '600',
    letterSpacing: -0.5,
    fontVariant: ['tabular-nums'],
  },
  title: { fontSize: 28, lineHeight: 34, fontWeight: '600', letterSpacing: -0.3 },
  headline: { fontSize: 17, lineHeight: 24, fontWeight: '600' },
  body: { fontSize: 16, lineHeight: 24, fontWeight: '400' },
  bodyStrong: { fontSize: 16, lineHeight: 24, fontWeight: '500' },
  caption: { fontSize: 14, lineHeight: 20, fontWeight: '400' },
  micro: { fontSize: 12, lineHeight: 16, fontWeight: '500' },
} as const satisfies Record<string, TextStyle>;

export const theme = {
  colors: {
    background: '#FAF9F6',
    surface: '#FFFFFF',
    ink: '#13233B',
    muted: '#657183',
    brand: '#153D85',
    pressed: '#102F68',
    blue: '#2864CF',
    paleBlue: '#EAF0FB',
    border: '#DFE3E8',
    // Neutral fill: pressed rows and icon buttons (one press feedback, DESIGN_DIRECTION §2.4).
    fill: '#F0F1F3',
    success: '#287152',
    paleSuccess: '#E7F1EB',
    warning: '#8B631D',
    paleWarning: '#F5EDDC',
    error: '#B23C3C',
    paleError: '#F8EAEA',
    disabled: '#E0E4EA',
    onBrand: '#FFFFFF',
    gold: '#B39A58',
    onDarkSecondary: '#BBC8DA',
    onDarkBorder: '#3B5070',
    onDarkSuccess: '#A0C5AD',
    captureSurface: '#EEF0F2',
    captureBorder: '#A3AEBC',
    captureDetail: '#B7C0CA',
    silhouette: '#CFD6DF',
  },
  text,
  space: { xs: 4, sm: 8, md: 12, lg: 16, xl: 24, xxl: 32, huge: 48 },
  // sm: inputs, chips, badges; md: buttons, banners, lists; lg: the balance card, sheets.
  radius: { sm: 8, md: 12, lg: 20, pill: 999, small: 8, medium: 12, large: 20 },
  type: { display: 42, title: 30, section: 20, body: 16, small: 14, caption: 12 },
  sizes: { touch: 48, minTouch: 44, button: 52, input: 52, gutter: 24, content: 520, status: 48 },
  motion: { quick: 180 },
  scrim: 'rgba(19,35,59,0.4)',
} as const;
