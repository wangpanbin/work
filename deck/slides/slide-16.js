// Slide 16 -- Summary and closing
const { addMappedText, addMappedShape } = require('./mavis-ppt-map.cjs');
const { addPageBadge, THEME, TOKENS, FONT_CN, FONT_EN } = require('./lib.js');

const TAKEAWAYS = [
  ['一致性优先', '位图 + Lua 把超卖从"可能发生"变成"结构上不可能"', 'Bitmap plus Lua makes overselling structurally impossible'],
  ['实时是体验的根', 'WebSocket 让座位状态无需刷新即可同步', 'WebSocket keeps the seat map in sync without refresh'],
  ['AI 应当有边界', '助手只给建议不代执行, 用架构决策而非提示词约束', 'Bounded AI: enforced by architecture, not prompting'],
  ['数字必须可复现', '279 个用例现场跑通, 截图取自真实运行', '279 cases re-run live; screenshots from the real stack']
];

const NEXT = [
  ['真实支付对接', '接入沙箱支付与真实退款流程', 'Sandbox payment integration'],
  ['多影厅排片', '支持跨厅连座与最优座位推荐', 'Cross-hall contiguous seating'],
  ['CI 流水线', 'GitHub Actions 自动跑双端测试', 'CI running both suites']
];

function createSlide(pres, theme) {
  const slide = pres.addSlide();
  slide.background = { color: THEME.bg };
  const map = pres.__mavisElementMap;
  map?.startSlide({ slideNumber: 16, slideId: 'summary', title: 'Summary' });

  addMappedText(slide, map, {
    elementId: 'sum.kicker', role: 'label',
    text: 'SUMMARY AND NEXT STEPS', x: 0.55, y: 0.42, w: 6, h: 0.24,
    options: {
      fontSize: 10, fontFace: FONT_EN, color: THEME.accent,
      charSpacing: 2, bold: true, margin: 0
    },
    source: { file: 'slides/slide-16.js', method: 'addText' }
  });
  addMappedText(slide, map, {
    elementId: 'sum.title', role: 'title',
    text: '总结与展望', x: 0.55, y: 0.66, w: 6, h: 0.5,
    options: {
      fontSize: 27, fontFace: FONT_CN, color: THEME.primary,
      bold: true, margin: 0
    },
    source: { file: 'slides/slide-16.js', method: 'addText' }
  });
  addMappedText(slide, map, {
    elementId: 'sum.titleEn', role: 'subtitle',
    text: 'What this project demonstrates, and where it goes next',
    x: 0.55, y: 1.16, w: 7, h: 0.24,
    options: {
      fontSize: 10.5, fontFace: FONT_EN, color: THEME.secondary, margin: 0
    },
    source: { file: 'slides/slide-16.js', method: 'addText' }
  });

  // Four takeaway cards, 2x2.
  TAKEAWAYS.forEach((t, i) => {
    const col = i % 2;
    const row = Math.floor(i / 2);
    const x = 0.55 + col * 4.55;
    const y = 1.58 + row * 1.06;
    addMappedShape(slide, map, pres.shapes.RECTANGLE, {
      elementId: `sum.card${i + 1}`, role: 'card',
      x, y, w: 4.35, h: 0.94,
      options: {
        fill: { color: THEME.light },
        line: { color: TOKENS.rule, width: 0.75 },
        shadow: { type: 'outer', color: '000000', blur: 6, offset: 1, angle: 90, opacity: 0.07 }
      },
      source: { file: 'slides/slide-16.js', method: 'addShape' }
    });
    addMappedShape(slide, map, pres.shapes.RECTANGLE, {
      elementId: `sum.cardBar${i + 1}`, role: 'decorative',
      x, y, w: 0.05, h: 0.94,
      options: { fill: { color: THEME.accent }, line: { color: THEME.accent, width: 0.5 } },
      source: { file: 'slides/slide-16.js', method: 'addShape' }
    });
    addMappedText(slide, map, {
      elementId: `sum.cardCn${i + 1}`, role: 'body',
      text: t[0], x: x + 0.18, y: y + 0.08, w: 4.0, h: 0.22,
      options: {
        fontSize: 11, fontFace: FONT_CN, color: THEME.accent,
        bold: true, margin: 0
      },
      source: { file: 'slides/slide-16.js', method: 'addText' }
    });
    addMappedText(slide, map, {
      elementId: `sum.cardBody${i + 1}`, role: 'body',
      text: t[1], x: x + 0.18, y: y + 0.31, w: 4.0, h: 0.24,
      options: {
        fontSize: 8, fontFace: FONT_CN, color: THEME.primary, margin: 0
      },
      source: { file: 'slides/slide-16.js', method: 'addText' }
    });
    addMappedText(slide, map, {
      elementId: `sum.cardEn${i + 1}`, role: 'body',
      text: t[2], x: x + 0.18, y: y + 0.58, w: 4.0, h: 0.3,
      options: {
        fontSize: 7, fontFace: FONT_EN, color: TOKENS.ink3,
        italic: true, margin: 0
      },
      source: { file: 'slides/slide-16.js', method: 'addText' }
    });
  });

  // Next steps strip.
  addMappedText(slide, map, {
    elementId: 'sum.nextHead', role: 'heading',
    text: '后续演进 / Next Steps', x: 0.55, y: 3.82, w: 4, h: 0.24,
    options: {
      fontSize: 11, fontFace: FONT_CN, color: THEME.accent,
      bold: true, margin: 0
    },
    source: { file: 'slides/slide-16.js', method: 'addText' }
  });

  NEXT.forEach((n, i) => {
    const x = 0.55 + i * 3.0;
    addMappedShape(slide, map, pres.shapes.RECTANGLE, {
      elementId: `sum.next${i + 1}`, role: 'card',
      x, y: 4.1, w: 2.9, h: 0.68,
      options: {
        fill: { color: 'FFFFFF' },
        line: { color: TOKENS.rule, width: 0.75 }
      },
      source: { file: 'slides/slide-16.js', method: 'addShape' }
    });
    addMappedText(slide, map, {
      elementId: `sum.nextCn${i + 1}`, role: 'body',
      text: n[0], x: x + 0.14, y: 4.2, w: 2.6, h: 0.2,
      options: {
        fontSize: 9.5, fontFace: FONT_CN, color: THEME.primary,
        bold: true, margin: 0
      },
      source: { file: 'slides/slide-16.js', method: 'addText' }
    });
    addMappedText(slide, map, {
      elementId: `sum.nextDesc${i + 1}`, role: 'body',
      text: n[1], x: x + 0.14, y: 4.38, w: 2.6, h: 0.18,
      options: {
        fontSize: 7.5, fontFace: FONT_CN, color: THEME.secondary, margin: 0
      },
      source: { file: 'slides/slide-16.js', method: 'addText' }
    });
    addMappedText(slide, map, {
      elementId: `sum.nextEn${i + 1}`, role: 'body',
      text: n[2], x: x + 0.14, y: 4.58, w: 2.6, h: 0.18,
      options: {
        fontSize: 6.5, fontFace: FONT_EN, color: TOKENS.ink3,
        italic: true, margin: 0
      },
      source: { file: 'slides/slide-16.js', method: 'addText' }
    });
  });

  addPageBadge(slide, map, 'sum', 16);
}

module.exports = { createSlide };
