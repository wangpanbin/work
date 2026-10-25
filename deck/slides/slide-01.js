// Slide 1 -- Cover
const { addMappedText, addMappedShape, addMappedImage } = require('./mavis-ppt-map.cjs');
const { THEME, TOKENS, FONT_CN, FONT_EN } = require('./theme.js');

function createSlide(pres, theme) {
  const slide = pres.addSlide();
  slide.background = { color: THEME.bg };
  const map = pres.__mavisElementMap;
  map?.startSlide({ slideNumber: 1, slideId: 'cover', title: 'Star Cinema' });

  // Right half: real product screenshot as the visual anchor.
  addMappedImage(slide, map, {
    elementId: 'cover.shot',
    role: 'image',
    path: '../assets/06-seat-select.png',
    x: 5.05, y: 0.62, w: 4.55, h: 2.56,
    source: { file: 'slides/slide-01.js', method: 'addImage' }
  });
  addMappedShape(slide, map, pres.shapes.RECTANGLE, {
    elementId: 'cover.shotFrame',
    role: 'decorative',
    x: 5.05, y: 0.62, w: 4.55, h: 2.56,
    options: {
      fill: { type: 'none' },
      line: { color: TOKENS.ruleStrong, width: 1 }
    },
    source: { file: 'slides/slide-01.js', method: 'addShape' }
  });

  addMappedText(slide, map, {
    elementId: 'cover.kicker',
    role: 'label',
    text: 'COURSE PROJECT  /  REALTIME SEAT BOOKING',
    x: 0.62, y: 0.78, w: 4.3, h: 0.24,
    options: {
      fontSize: 9.5, fontFace: FONT_EN, color: THEME.accent,
      charSpacing: 1.6, bold: true, margin: 0
    },
    source: { file: 'slides/slide-01.js', method: 'addText' }
  });

  addMappedText(slide, map, {
    elementId: 'cover.title',
    role: 'title',
    text: '影院抢票选座系统',
    x: 0.62, y: 1.1, w: 4.3, h: 0.62,
    options: {
      fontSize: 33, fontFace: FONT_CN, color: THEME.primary,
      bold: true, margin: 0, fit: 'shrink'
    },
    source: { file: 'slides/slide-01.js', method: 'addText' }
  });

  addMappedText(slide, map, {
    elementId: 'cover.titleEn',
    role: 'subtitle',
    text: 'Star Cinema - Flash-Sale Ticketing\nwith Real-Time Seat Locking',
    x: 0.62, y: 1.78, w: 4.3, h: 0.62,
    options: {
      fontSize: 13, fontFace: FONT_EN, color: THEME.secondary,
      margin: 0, lineSpacingMultiple: 1.15
    },
    source: { file: 'slides/slide-01.js', method: 'addText' }
  });

  addMappedShape(slide, map, pres.shapes.RECTANGLE, {
    elementId: 'cover.rule',
    role: 'decorative',
    x: 0.62, y: 2.56, w: 4.3, h: 0.012,
    options: { fill: { color: TOKENS.ruleStrong }, line: { color: TOKENS.ruleStrong, width: 0.5 } },
    source: { file: 'slides/slide-01.js', method: 'addShape' }
  });

  const facts = [
    ['并发锁座 + 幂等 + 限流', 'Atomic seat lock with idempotency and rate limiting'],
    ['Redis 位图 + Lua 原子操作', 'Redis bitmap with atomic Lua scripts'],
    ['对话式订票助手 (只读)', 'Conversational booking assistant (read-only)'],
    ['中英双语运行时切换', 'Runtime zh-CN / en-US switching']
  ];
  facts.forEach((f, i) => {
    const y = 2.76 + i * 0.42;
    addMappedShape(slide, map, pres.shapes.RECTANGLE, {
      elementId: `cover.dot${i + 1}`,
      role: 'decorative',
      x: 0.62, y: y + 0.07, w: 0.07, h: 0.07,
      options: { fill: { color: THEME.accent }, line: { color: THEME.accent, width: 0.5 } },
      source: { file: 'slides/slide-01.js', method: 'addShape' }
    });
    addMappedText(slide, map, {
      elementId: `cover.fact${i + 1}`,
      role: 'body',
      text: `${f[0]}    ${f[1]}`,
      x: 0.82, y, w: 3.8, h: 0.26,
      options: {
        fontSize: 9, fontFace: FONT_CN, color: THEME.primary, margin: 0
      },
      source: { file: 'slides/slide-01.js', method: 'addText' }
    });
  });

  addMappedText(slide, map, {
    elementId: 'cover.meta',
    role: 'label',
    text: 'Spring Boot 3.5 + MyBatis-Plus + Redis 8 + WebSocket   |   Vue 3 + Vite + TypeScript + Element Plus',
    x: 5.05, y: 3.36, w: 4.55, h: 0.44,
    options: {
      fontSize: 8.5, fontFace: FONT_EN, color: THEME.secondary, margin: 0
    },
    source: { file: 'slides/slide-01.js', method: 'addText' }
  });
  addMappedText(slide, map, {
    elementId: 'cover.metaCn',
    role: 'label',
    text: '全部截图取自本地真实运行环境 (2026-10-01)   /   All screenshots captured from the live local stack',
    x: 5.05, y: 3.86, w: 4.55, h: 0.4,
    options: {
      fontSize: 8.5, fontFace: FONT_CN, color: TOKENS.ink3, margin: 0
    },
    source: { file: 'slides/slide-01.js', method: 'addText' }
  });
}

module.exports = { createSlide };
