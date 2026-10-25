// Slide 5 -- Section divider 01
const { addMappedText, addMappedShape } = require('./mavis-ppt-map.cjs');
const { addPageBadge, THEME, TOKENS, FONT_CN, FONT_EN } = require('./lib.js');

function createSlide(pres, theme) {
  const slide = pres.addSlide();
  slide.background = { color: TOKENS.hall };
  const map = pres.__mavisElementMap;
  map?.startSlide({ slideNumber: 5, slideId: 'section-01', title: 'Core Challenges' });

  addMappedShape(slide, map, pres.shapes.RECTANGLE, {
    elementId: 'sec01.block', role: 'decorative',
    x: 0, y: 0, w: 3.1, h: 5.625,
    options: { fill: { color: THEME.accent }, line: { color: THEME.accent, width: 0.5 } },
    source: { file: 'slides/slide-05.js', method: 'addShape' }
  });

  addMappedText(slide, map, {
    elementId: 'sec01.num', role: 'value',
    text: '01', x: 0.7, y: 1.9, w: 2.2, h: 1.4,
    options: {
      fontSize: 78, fontFace: FONT_EN, color: 'FFFFFF',
      bold: true, margin: 0
    },
    source: { file: 'slides/slide-05.js', method: 'addText' }
  });

  addMappedText(slide, map, {
    elementId: 'sec01.title', role: 'title',
    text: '四大技术难点', x: 3.75, y: 2.12, w: 5.6, h: 0.56,
    options: {
      fontSize: 32, fontFace: FONT_CN, color: TOKENS.hallInk,
      bold: true, margin: 0
    },
    source: { file: 'slides/slide-05.js', method: 'addText' }
  });
  addMappedText(slide, map, {
    elementId: 'sec01.titleEn', role: 'subtitle',
    text: 'Four Core Engineering Challenges', x: 3.75, y: 2.72, w: 5.6, h: 0.3,
    options: {
      fontSize: 14, fontFace: FONT_EN, color: 'B9A88C', margin: 0
    },
    source: { file: 'slides/slide-05.js', method: 'addText' }
  });
  addMappedShape(slide, map, pres.shapes.RECTANGLE, {
    elementId: 'sec01.rule', role: 'decorative',
    x: 3.75, y: 3.18, w: 1.1, h: 0.035,
    options: { fill: { color: THEME.accent }, line: { color: THEME.accent, width: 0.5 } },
    source: { file: 'slides/slide-05.js', method: 'addShape' }
  });

  const items = [
    ['并发锁座与超卖防护', 'Atomic seat locking'],
    ['幂等 / 限流 / 一致性', 'Idempotency and rate limiting'],
    ['延迟关单无 MQ 方案', 'Delayed closing without a message queue'],
    ['实时座位 WebSocket 同步', 'Real-time seat sync over WebSocket']
  ];
  items.forEach((t, i) => {
    addMappedText(slide, map, {
      elementId: `sec01.item${i + 1}`, role: 'body',
      text: t[0], x: 3.75, y: 3.4 + i * 0.42, w: 5.6, h: 0.24,
      options: {
        fontSize: 11, fontFace: FONT_CN, color: TOKENS.hallInk, margin: 0
      },
      source: { file: 'slides/slide-05.js', method: 'addText' }
    });
    addMappedText(slide, map, {
      elementId: `sec01.itemEn${i + 1}`, role: 'body',
      text: t[1], x: 3.75, y: 3.61 + i * 0.42, w: 5.6, h: 0.2,
      options: {
        fontSize: 8, fontFace: FONT_EN, color: '9C9284',
        italic: true, margin: 0
      },
      source: { file: 'slides/slide-05.js', method: 'addText' }
    });
  });

  addPageBadge(slide, map, 'sec01', 5);
}

module.exports = { createSlide };
