// Shared design tokens for the cinema deck.
// Palette is taken from the product's own CSS custom properties
// (cinema-web/src/styles/main.css) so slide background matches screenshots.
//
// paper  #f2ece1  bg        (light ticket-stub paper)
// ink    #1f1b16  primary   (body text)
// ink2   #574f43  secondary (muted text / English lines)
// accent #b8352a  accent    (vermilion single print color)
// ok     #2f6b4f  (functional green)

const THEME = {
  primary: '1F1B16',
  secondary: '574F43',
  accent: 'B8352A',
  light: 'FAF7F0',
  bg: 'F2ECE1',
  // Functional colors. These live on THEME (not only TOKENS) because
  // compile.js passes THEME into every slide; referencing THEME.ok on a
  // 5-key object yields undefined, which PptxGenJS silently renders black.
  ok: '2F6B4F',
  warn: '8A6D1F',
  hall: '171410',
  hallInk: 'E8E0D2',
  paperSunk: 'E7DDCB',
  rule: 'D6C9B3',
  ruleStrong: 'B9A88C',
  ink3: '8A8071',
  accentWash: 'F8EBEA'
};

// Extra tokens used across slides (mirrors the THEME functional colors).
const TOKENS = {
  paperSunk: 'E7DDCB',
  rule: 'D6C9B3',
  ruleStrong: 'B9A88C',
  ink3: '8A8071',
  accentWash: 'F8EBEA',
  ok: '2F6B4F',
  warn: '8A6D1F',
  hall: '171410',
  hallInk: 'E8E0D2',
  admin: 'EEF0F1',
  adminRaised: 'F7F8F9'
};

const FONT_CN = 'Microsoft YaHei';
const FONT_EN = 'Arial';

const PAGE_W = 10;
const PAGE_H = 5.625;

// Fresh object every call -- PptxGenJS mutates option objects in place.
const softShadow = () => ({
  type: 'outer',
  color: '000000',
  blur: 8,
  offset: 2,
  angle: 90,
  opacity: 0.08
});

module.exports = { THEME, TOKENS, FONT_CN, FONT_EN, PAGE_W, PAGE_H, softShadow };
