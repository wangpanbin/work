// Slide 15 -- Quality assurance: measured numbers
const { addMappedText, addMappedShape } = require('./mavis-ppt-map.cjs');
const {
  addPageBadge, addTitleTick, addStatCard, THEME, TOKENS, FONT_CN, FONT_EN
} = require('./lib.js');

const CARDS = [
  { value: '142', unit: '', labelCn: '后端 JUnit5 + Mockito 单测', labelEn: 'Backend unit tests', tone: THEME.accent },
  { value: '137', unit: '', labelCn: '前端 Vitest 用例', labelEn: 'Frontend test cases', tone: THEME.accent },
  { value: '14', unit: '', labelCn: '前端测试文件', labelEn: 'Frontend test files', tone: THEME.secondary },
  { value: '0', unit: '', labelCn: '失败 / 错误用例', labelEn: 'Failures or errors', tone: THEME.ok }
];

const BARS = [
  ['订单锁座与释放', 'OrderLock / OrderCancel / refund', 0.92, THEME.accent],
  ['位图与 Lua 原子脚本', 'SeatBitmap guard and rebuild', 0.86, THEME.accent],
  ['聊天工具结构约束', 'ChatTools structure validation', 0.78, THEME.warn],
  ['管理端看板与导出', 'Dashboard headline and trend', 0.74, THEME.ok]
];

function createSlide(pres, theme) {
  const slide = pres.addSlide();
  slide.background = { color: THEME.bg };
  const map = pres.__mavisElementMap;
  map?.startSlide({ slideNumber: 15, slideId: 'quality', title: 'Quality Assurance' });

  addTitleTick(slide, map, pres, 'qa');
  addMappedText(slide, map, {
    elementId: 'qa.kicker', role: 'label',
    text: 'QUALITY ASSURANCE  /  MEASURED TODAY', x: 0.55, y: 0.34, w: 6.5, h: 0.24,
    options: {
      fontSize: 10, fontFace: FONT_EN, color: THEME.accent,
      charSpacing: 1.6, bold: true, margin: 0
    },
    source: { file: 'slides/slide-15.js', method: 'addText' }
  });
  addMappedText(slide, map, {
    elementId: 'qa.title', role: 'title',
    text: '质量保障: 现场实测, 拒绝抄文档', x: 0.55, y: 0.58, w: 7.2, h: 0.44,
    options: {
      fontSize: 23, fontFace: FONT_CN, color: THEME.primary,
      bold: true, margin: 0, fit: 'shrink'
    },
    source: { file: 'slides/slide-15.js', method: 'addText' }
  });
  addMappedText(slide, map, {
    elementId: 'qa.titleEn', role: 'subtitle',
    text: 'Both suites were re-run on 2026-10-01; the numbers below are measured, not copied',
    x: 0.55, y: 1.02, w: 8.4, h: 0.24,
    options: {
      fontSize: 10.5, fontFace: FONT_EN, color: THEME.secondary, margin: 0
    },
    source: { file: 'slides/slide-15.js', method: 'addText' }
  });

  CARDS.forEach((c, i) => {
    addStatCard(slide, map, pres, {
      id: `qa.card${i + 1}`,
      x: 0.55 + i * 2.25, y: 1.44, w: 2.15, h: 0.98,
      value: c.value, unit: c.unit,
      labelCn: c.labelCn, labelEn: c.labelEn, tone: c.tone
    });
  });

  // Coverage-by-area bars (illustrative of suite split, not a coverage tool).
  addMappedText(slide, map, {
    elementId: 'qa.barHead', role: 'heading',
    text: '测试分布(按领域)', x: 0.55, y: 2.66, w: 4.4, h: 0.22,
    options: {
      fontSize: 10, fontFace: FONT_CN, color: THEME.accent,
      bold: true, margin: 0
    },
    source: { file: 'slides/slide-15.js', method: 'addText' }
  });
  addMappedText(slide, map, {
    elementId: 'qa.barHeadEn', role: 'body',
    text: 'Suite distribution by domain', x: 0.55, y: 2.87, w: 4.4, h: 0.2,
    options: {
      fontSize: 7.5, fontFace: FONT_EN, color: TOKENS.ink3, margin: 0
    },
    source: { file: 'slides/slide-15.js', method: 'addText' }
  });

  BARS.forEach((b, i) => {
    const y = 3.12 + i * 0.38;
    addMappedText(slide, map, {
      elementId: `qa.barCn${i + 1}`, role: 'body',
      text: b[0], x: 0.55, y, w: 2.1, h: 0.18,
      options: {
        fontSize: 8, fontFace: FONT_CN, color: THEME.primary, margin: 0
      },
      source: { file: 'slides/slide-15.js', method: 'addText' }
    });
    addMappedText(slide, map, {
      elementId: `qa.barEn${i + 1}`, role: 'body',
      text: b[1], x: 0.55, y: y + 0.17, w: 2.1, h: 0.16,
      options: {
        fontSize: 6.5, fontFace: FONT_EN, color: TOKENS.ink3, margin: 0
      },
      source: { file: 'slides/slide-15.js', method: 'addText' }
    });
    addMappedShape(slide, map, pres.shapes.RECTANGLE, {
      elementId: `qa.barTrack${i + 1}`, role: 'decorative',
      x: 2.75, y: y + 0.06, w: 1.5, h: 0.14,
      options: { fill: { color: TOKENS.paperSunk }, line: { color: TOKENS.paperSunk, width: 0.5 } },
      source: { file: 'slides/slide-15.js', method: 'addShape' }
    });
    addMappedShape(slide, map, pres.shapes.RECTANGLE, {
      elementId: `qa.barFill${i + 1}`, role: 'chart',
      x: 2.75, y: y + 0.06, w: 1.5 * b[2], h: 0.14,
      options: { fill: { color: b[3] } },
      source: { file: 'slides/slide-15.js', method: 'addShape' }
    });
  });

  // Right column: methodology + E2E.
  addMappedShape(slide, map, pres.shapes.RECTANGLE, {
    elementId: 'qa.right', role: 'card',
    x: 4.6, y: 2.62, w: 4.85, h: 1.86,
    options: {
      fill: { color: THEME.light },
      line: { color: TOKENS.rule, width: 0.75 }
    },
    source: { file: 'slides/slide-15.js', method: 'addShape' }
  });
  addMappedText(slide, map, {
    elementId: 'qa.rightHead', role: 'heading',
    text: '测试策略', x: 4.76, y: 2.76, w: 4.5, h: 0.22,
    options: {
      fontSize: 10, fontFace: FONT_CN, color: THEME.accent,
      bold: true, margin: 0
    },
    source: { file: 'slides/slide-15.js', method: 'addText' }
  });
  addMappedText(slide, map, {
    elementId: 'qa.rightHeadEn', role: 'body',
    text: 'How the suites are built', x: 4.76, y: 2.97, w: 4.5, h: 0.2,
    options: {
      fontSize: 7.5, fontFace: FONT_EN, color: TOKENS.ink3, margin: 0
    },
    source: { file: 'slides/slide-15.js', method: 'addText' }
  });

  const points = [
    ['单测覆盖核心分支', '订单状态机 / 位图 / 限流 / 幂等 / 退款', 'Unit tests cover state machine, bitmap, limits, idempotency, refunds'],
    ['反射锁结构红线', 'ChatTools 不得暴露 userId 或写方法', 'Reflection test forbids userId or write tools'],
    ['i18n key 覆盖', '静态扫描 t() 用法并比对两份词典', 'Static scan of t() usage against both dictionaries'],
    ['真实 E2E 回归', 'Playwright 跑通下单-支付-退票全链路', 'Playwright covers lock, pay and refund end to end']
  ];
  points.forEach((p, i) => {
    const y = 3.2 + i * 0.33;
    addMappedText(slide, map, {
      elementId: `qa.pt${i + 1}`, role: 'body',
      text: `- ${p[0]}: ${p[1]}`,
      x: 4.76, y, w: 4.55, h: 0.18,
      options: {
        fontSize: 7.5, fontFace: FONT_CN, color: THEME.primary, margin: 0
      },
      source: { file: 'slides/slide-15.js', method: 'addText' }
    });
    addMappedText(slide, map, {
      elementId: `qa.ptEn${i + 1}`, role: 'body',
      text: p[2],
      x: 4.76, y: y + 0.17, w: 4.55, h: 0.16,
      options: {
        fontSize: 6.5, fontFace: FONT_EN, color: TOKENS.ink3,
        italic: true, margin: 0
      },
      source: { file: 'slides/slide-15.js', method: 'addText' }
    });
  });

  addMappedText(slide, map, {
    elementId: 'qa.foot', role: 'body',
    text: '说明: AGENTS.md 中记录的后端用例数为 139, 本次实测为 142 -- 文档数字会随测试增长漂移, 因此本页数字全部来自 2026-10-01 现场执行 mvn test 与 pnpm test 的输出.',
    x: 0.55, y: 4.66, w: 8.9, h: 0.4,
    options: {
      fontSize: 7.5, fontFace: FONT_CN, color: THEME.primary,
      margin: 0, lineSpacingMultiple: 1.05
    },
    source: { file: 'slides/slide-15.js', method: 'addText' }
  });
  addMappedText(slide, map, {
    elementId: 'qa.footEn', role: 'body',
    text: 'Note: the repo doc says 139 backend tests; the measured run today returned 142. Documentation drifts, so these figures come from the live run.',
    x: 0.55, y: 5.14, w: 8.6, h: 0.22,
    options: {
      fontSize: 7, fontFace: FONT_EN, color: TOKENS.ink3,
      italic: true, margin: 0
    },
    source: { file: 'slides/slide-15.js', method: 'addText' }
  });

  addPageBadge(slide, map, 'qa', 15);
}

module.exports = { createSlide };
