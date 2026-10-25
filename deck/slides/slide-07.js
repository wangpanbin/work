// Slide 7 -- Challenge 2: idempotency, rate limiting, consistency matrix
const { addMappedText, addMappedShape } = require('./mavis-ppt-map.cjs');
const { addPageBadge, addTitleTick, THEME, TOKENS, FONT_CN, FONT_EN } = require('./lib.js');

const MATRIX = [
  ['锁座冲突', '40002', '返回冲突座位, 前端刷新座位图', 'Seat conflict, client refreshes map', THEME.accent],
  ['重复提交', '40900', '幂等键命中直接返回首次结果', 'Idempotency key returns first result', TOKENS.warn],
  ['请求过频', '42900', '限流窗口内拒绝并提示稍后', 'Rate limited within window', THEME.ok],
  ['登录失效', '40101', '清理本地 token 并跳转登录', 'Token cleared, redirect to login', THEME.secondary]
];

const ANNOTATIONS = [
  ['@Idempotent', '幂等键 Idempotency-Key 落 Redis, 重复请求不再重复扣座', 'Redis-backed key dedupes repeat requests'],
  ['@RateLimit', '按用户 + 接口维度滑动窗口限流, 防刷座', 'Sliding window per user and per endpoint'],
  ['位图三脚本', 'release / confirm / refund 语义严格区分, 不可混用', 'Three Lua scripts, never interchangeable']
];

function createSlide(pres, theme) {
  const slide = pres.addSlide();
  slide.background = { color: THEME.bg };
  const map = pres.__mavisElementMap;
  map?.startSlide({ slideNumber: 7, slideId: 'challenge-guard', title: 'Idempotency and Consistency' });

  addTitleTick(slide, map, pres, 'c2');
  addMappedText(slide, map, {
    elementId: 'c2.kicker', role: 'label',
    text: 'CHALLENGE 02  /  IDEMPOTENCY AND CONSISTENCY', x: 0.55, y: 0.34, w: 6.5, h: 0.24,
    options: {
      fontSize: 10, fontFace: FONT_EN, color: THEME.accent,
      charSpacing: 1.6, bold: true, margin: 0
    },
    source: { file: 'slides/slide-07.js', method: 'addText' }
  });
  addMappedText(slide, map, {
    elementId: 'c2.title', role: 'title',
    text: '幂等, 限流与一致性防护', x: 0.55, y: 0.58, w: 6.6, h: 0.44,
    options: {
      fontSize: 24, fontFace: FONT_CN, color: THEME.primary,
      bold: true, margin: 0
    },
    source: { file: 'slides/slide-07.js', method: 'addText' }
  });
  addMappedText(slide, map, {
    elementId: 'c2.titleEn', role: 'subtitle',
    text: 'Annotations carry the guardrails; distinct error codes drive the UI',
    x: 0.55, y: 1.02, w: 6.6, h: 0.24,
    options: {
      fontSize: 10.5, fontFace: FONT_EN, color: THEME.secondary, margin: 0
    },
    source: { file: 'slides/slide-07.js', method: 'addText' }
  });

  // Consistency matrix table.
  const heads = ['场景 / Scenario', '错误码 / Code', '处理 / Handling', 'Notes'];
  const colX = [0.55, 1.95, 2.85, 6.15];
  const colW = [1.4, 0.9, 3.3, 3.3];

  addMappedShape(slide, map, pres.shapes.RECTANGLE, {
    elementId: 'c2.thead', role: 'table',
    x: 0.55, y: 1.5, w: 8.9, h: 0.34,
    options: { fill: { color: TOKENS.paperSunk }, line: { color: TOKENS.paperSunk, width: 0.5 } },
    source: { file: 'slides/slide-07.js', method: 'addShape' }
  });
  heads.forEach((h, i) => {
    addMappedText(slide, map, {
      elementId: `c2.th${i + 1}`, role: 'table',
      text: h, x: colX[i] + 0.1, y: 1.57, w: colW[i] - 0.15, h: 0.22,
      options: {
        fontSize: 8.5, fontFace: FONT_CN, color: THEME.primary,
        bold: true, margin: 0
      },
      source: { file: 'slides/slide-07.js', method: 'addText' }
    });
  });

  MATRIX.forEach((r, i) => {
    const y = 1.9 + i * 0.42;
    addMappedShape(slide, map, pres.shapes.RECTANGLE, {
      elementId: `c2.row${i + 1}`, role: 'table',
      x: 0.55, y, w: 8.9, h: 0.36,
      options: {
        fill: { color: i % 2 === 0 ? THEME.light : 'F5F0E6' },
        line: { color: TOKENS.rule, width: 0.5 }
      },
      source: { file: 'slides/slide-07.js', method: 'addShape' }
    });
    addMappedShape(slide, map, pres.shapes.RECTANGLE, {
      elementId: `c2.rowMark${i + 1}`, role: 'decorative',
      x: 0.55, y, w: 0.045, h: 0.36,
      options: { fill: { color: r[4] } },
      source: { file: 'slides/slide-07.js', method: 'addShape' }
    });
    addMappedText(slide, map, {
      elementId: `c2.c1_${i + 1}`, role: 'table',
      text: r[0], x: colX[0] + 0.16, y: y + 0.09, w: colW[0] - 0.2, h: 0.2,
      options: {
        fontSize: 9, fontFace: FONT_CN, color: THEME.primary,
        bold: true, margin: 0
      },
      source: { file: 'slides/slide-07.js', method: 'addText' }
    });
    addMappedText(slide, map, {
      elementId: `c2.c2_${i + 1}`, role: 'table',
      text: r[1], x: colX[1] + 0.06, y: y + 0.09, w: colW[1] - 0.15, h: 0.2,
      options: {
        fontSize: 9, fontFace: FONT_EN, color: r[4],
        bold: true, margin: 0
      },
      source: { file: 'slides/slide-07.js', method: 'addText' }
    });
    addMappedText(slide, map, {
      elementId: `c2.c3_${i + 1}`, role: 'table',
      text: r[2], x: colX[2] + 0.06, y: y + 0.09, w: colW[2] - 0.15, h: 0.2,
      options: {
        fontSize: 8.5, fontFace: FONT_CN, color: THEME.primary, margin: 0
      },
      source: { file: 'slides/slide-07.js', method: 'addText' }
    });
    addMappedText(slide, map, {
      elementId: `c2.c4_${i + 1}`, role: 'table',
      text: r[3], x: colX[3] + 0.06, y: y + 0.1, w: colW[3] - 0.15, h: 0.2,
      options: {
        fontSize: 7.5, fontFace: FONT_EN, color: TOKENS.ink3, margin: 0
      },
      source: { file: 'slides/slide-07.js', method: 'addText' }
    });
  });

  // Three annotations.
  ANNOTATIONS.forEach((a, i) => {
    const x = 0.55 + i * 3.0;
    addMappedShape(slide, map, pres.shapes.RECTANGLE, {
      elementId: `c2.ann${i + 1}`, role: 'card',
      x, y: 3.72, w: 2.9, h: 1.1,
      options: {
        fill: { color: THEME.light },
        line: { color: TOKENS.rule, width: 0.75 }
      },
      source: { file: 'slides/slide-07.js', method: 'addShape' }
    });
    addMappedText(slide, map, {
      elementId: `c2.annCn${i + 1}`, role: 'body',
      text: a[0], x: x + 0.14, y: 3.82, w: 2.62, h: 0.22,
      options: {
        fontSize: 9.5, fontFace: FONT_CN, color: THEME.accent,
        bold: true, margin: 0
      },
      source: { file: 'slides/slide-07.js', method: 'addText' }
    });
    addMappedText(slide, map, {
      elementId: `c2.annBody${i + 1}`, role: 'body',
      text: a[1], x: x + 0.14, y: 4.05, w: 2.62, h: 0.4,
      options: {
        fontSize: 7.5, fontFace: FONT_CN, color: THEME.primary,
        margin: 0, lineSpacingMultiple: 1.05
      },
      source: { file: 'slides/slide-07.js', method: 'addText' }
    });
    addMappedText(slide, map, {
      elementId: `c2.annEn${i + 1}`, role: 'body',
      text: a[2], x: x + 0.14, y: 4.5, w: 2.62, h: 0.26,
      options: {
        fontSize: 7, fontFace: FONT_EN, color: TOKENS.ink3,
        italic: true, margin: 0
      },
      source: { file: 'slides/slide-07.js', method: 'addText' }
    });
  });

  addPageBadge(slide, map, 'c2', 7);
}

module.exports = { createSlide };
