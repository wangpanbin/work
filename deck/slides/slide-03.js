// Slide 3 -- Background and goals
const { addMappedText, addMappedShape, addMappedImage } = require('./mavis-ppt-map.cjs');
const { addPageBadge, addTitleTick, THEME, TOKENS, FONT_CN, FONT_EN } = require('./lib.js');

const PROBLEMS = [
  ['超卖风险', '并发抢座导致同一座位被多次售出', 'Overselling under concurrent seat selection'],
  ['体验割裂', '用户需在多个页面间反复跳转', 'Fragmented multi-page booking flow'],
  ['人工压力', '影院运营依赖人工统计营收', 'Manual revenue reconciliation for operators']
];

const GOALS = [
  ['强一致锁座', 'Redis 位图 + Lua 原子扣减, 杜绝超卖', 'Oversell-proof atomic seat locking'],
  ['实时可见', 'WebSocket 推送, 座位状态毫秒级同步', 'Real-time seat state over WebSocket'],
  ['智能辅助', '对话式助手给出选座建议而不代下单', 'Advisory assistant that never places orders'],
  ['可运营', '经营看板 + 营收导出 Excel', 'Ops dashboard with Excel export']
];

function createSlide(pres, theme) {
  const slide = pres.addSlide();
  slide.background = { color: THEME.bg };
  const map = pres.__mavisElementMap;
  map?.startSlide({ slideNumber: 3, slideId: 'background', title: 'Background and Goals' });

  addTitleTick(slide, map, pres, 'bg');
  addMappedText(slide, map, {
    elementId: 'bg.kicker', role: 'label',
    text: '01  BACKGROUND AND GOALS', x: 0.55, y: 0.34, w: 6, h: 0.24,
    options: {
      fontSize: 10, fontFace: FONT_EN, color: THEME.accent,
      charSpacing: 2, bold: true, margin: 0
    },
    source: { file: 'slides/slide-03.js', method: 'addText' }
  });
  addMappedText(slide, map, {
    elementId: 'bg.title', role: 'title',
    text: '项目背景与目标', x: 0.55, y: 0.58, w: 6, h: 0.44,
    options: {
      fontSize: 24, fontFace: FONT_CN, color: THEME.primary,
      bold: true, margin: 0
    },
    source: { file: 'slides/slide-03.js', method: 'addText' }
  });
  addMappedText(slide, map, {
    elementId: 'bg.titleEn', role: 'subtitle',
    text: 'Why this project, and what it must achieve',
    x: 0.55, y: 1.02, w: 6, h: 0.24,
    options: {
      fontSize: 10.5, fontFace: FONT_EN, color: THEME.secondary, margin: 0
    },
    source: { file: 'slides/slide-03.js', method: 'addText' }
  });

  // Left column: the three problems.
  addMappedText(slide, map, {
    elementId: 'bg.probHead', role: 'heading',
    text: '痛点 / Pain Points', x: 0.55, y: 1.48, w: 4.4, h: 0.24,
    options: {
      fontSize: 11, fontFace: FONT_CN, color: THEME.accent,
      bold: true, margin: 0
    },
    source: { file: 'slides/slide-03.js', method: 'addText' }
  });

  PROBLEMS.forEach((p, i) => {
    const y = 1.8 + i * 0.92;
    addMappedShape(slide, map, pres.shapes.RECTANGLE, {
      elementId: `bg.prob${i + 1}`, role: 'card',
      x: 0.55, y, w: 4.4, h: 0.8,
      options: {
        fill: { color: THEME.light },
        line: { color: TOKENS.rule, width: 0.75 }
      },
      source: { file: 'slides/slide-03.js', method: 'addShape' }
    });
    addMappedShape(slide, map, pres.shapes.RECTANGLE, {
      elementId: `bg.probMark${i + 1}`, role: 'decorative',
      x: 0.55, y, w: 0.045, h: 0.8,
      options: { fill: { color: TOKENS.ink3 }, line: { color: TOKENS.ink3, width: 0.5 } },
      source: { file: 'slides/slide-03.js', method: 'addShape' }
    });
    addMappedText(slide, map, {
      elementId: `bg.probCn${i + 1}`, role: 'body',
      text: p[0], x: 0.74, y: y + 0.08, w: 4.1, h: 0.22,
      options: {
        fontSize: 11.5, fontFace: FONT_CN, color: THEME.primary,
        bold: true, margin: 0
      },
      source: { file: 'slides/slide-03.js', method: 'addText' }
    });
    addMappedText(slide, map, {
      elementId: `bg.probDesc${i + 1}`, role: 'body',
      text: p[1], x: 0.74, y: y + 0.3, w: 4.1, h: 0.2,
      options: {
        fontSize: 9, fontFace: FONT_CN, color: THEME.secondary, margin: 0
      },
      source: { file: 'slides/slide-03.js', method: 'addText' }
    });
    addMappedText(slide, map, {
      elementId: `bg.probEn${i + 1}`, role: 'body',
      text: p[2], x: 0.74, y: y + 0.5, w: 4.1, h: 0.2,
      options: {
        fontSize: 8.5, fontFace: FONT_EN, color: TOKENS.ink3,
        italic: true, margin: 0
      },
      source: { file: 'slides/slide-03.js', method: 'addText' }
    });
  });

  // Right column: goals + real product screenshot.
  addMappedText(slide, map, {
    elementId: 'bg.goalHead', role: 'heading',
    text: '设计目标 / Design Goals', x: 5.15, y: 1.48, w: 4.3, h: 0.24,
    options: {
      fontSize: 11, fontFace: FONT_CN, color: THEME.accent,
      bold: true, margin: 0
    },
    source: { file: 'slides/slide-03.js', method: 'addText' }
  });

  GOALS.forEach((g, i) => {
    const y = 1.8 + i * 0.46;
    addMappedText(slide, map, {
      elementId: `bg.goalNo${i + 1}`, role: 'label',
      text: String(i + 1).padStart(2, '0'),
      x: 5.15, y, w: 0.3, h: 0.22,
      options: {
        fontSize: 9.5, fontFace: FONT_EN, color: THEME.accent,
        bold: true, margin: 0
      },
      source: { file: 'slides/slide-03.js', method: 'addText' }
    });
    addMappedText(slide, map, {
      elementId: `bg.goalCn${i + 1}`, role: 'body',
      text: `${g[0]} - ${g[1]}`, x: 5.52, y, w: 3.9, h: 0.22,
      options: {
        fontSize: 9.5, fontFace: FONT_CN, color: THEME.primary, margin: 0
      },
      source: { file: 'slides/slide-03.js', method: 'addText' }
    });
    addMappedText(slide, map, {
      elementId: `bg.goalEn${i + 1}`, role: 'body',
      text: g[2], x: 5.52, y: y + 0.21, w: 3.9, h: 0.2,
      options: {
        fontSize: 8, fontFace: FONT_EN, color: TOKENS.ink3, margin: 0
      },
      source: { file: 'slides/slide-03.js', method: 'addText' }
    });
  });

  addMappedImage(slide, map, {
    elementId: 'bg.shot', role: 'image',
    path: '../assets/01-home-zh.png',
    x: 5.15, y: 3.72, w: 4.3, h: 1.42,
    source: { file: 'slides/slide-03.js', method: 'addImage' }
  });
  addMappedShape(slide, map, pres.shapes.RECTANGLE, {
    elementId: 'bg.shotFrame', role: 'decorative',
    x: 5.15, y: 3.72, w: 4.3, h: 1.42,
    options: {
      fill: { type: 'none' },
      line: { color: TOKENS.ruleStrong, width: 1 }
    },
    source: { file: 'slides/slide-03.js', method: 'addShape' }
  });
  addMappedText(slide, map, {
    elementId: 'bg.shotCap', role: 'label',
    text: '首页 17 部热映影片  /  Now-showing homepage, 17 seeded titles',
    x: 5.15, y: 5.17, w: 4.3, h: 0.2,
    options: {
      fontSize: 8, fontFace: FONT_CN, color: TOKENS.ink3, margin: 0
    },
    source: { file: 'slides/slide-03.js', method: 'addText' }
  });

  addPageBadge(slide, map, 'bg', 3);
}

module.exports = { createSlide };
