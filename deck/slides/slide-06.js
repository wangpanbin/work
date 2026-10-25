// Slide 6 -- Challenge 1: atomic seat locking (bitmap + Lua)
const { addMappedText, addMappedShape, addMappedImage } = require('./mavis-ppt-map.cjs');
const { addPageBadge, addTitleTick, THEME, TOKENS, FONT_CN, FONT_EN } = require('./lib.js');

function createSlide(pres, theme) {
  const slide = pres.addSlide();
  slide.background = { color: THEME.bg };
  const map = pres.__mavisElementMap;
  map?.startSlide({ slideNumber: 6, slideId: 'challenge-lock', title: 'Atomic Seat Locking' });

  addTitleTick(slide, map, pres, 'c1');
  addMappedText(slide, map, {
    elementId: 'c1.kicker', role: 'label',
    text: 'CHALLENGE 01  /  ATOMIC SEAT LOCKING', x: 0.55, y: 0.34, w: 6.5, h: 0.24,
    options: {
      fontSize: 10, fontFace: FONT_EN, color: THEME.accent,
      charSpacing: 1.6, bold: true, margin: 0
    },
    source: { file: 'slides/slide-06.js', method: 'addText' }
  });
  addMappedText(slide, map, {
    elementId: 'c1.title', role: 'title',
    text: '并发锁座: 位图 + Lua 原子扣减', x: 0.55, y: 0.58, w: 6.6, h: 0.44,
    options: {
      fontSize: 22, fontFace: FONT_CN, color: THEME.primary,
      bold: true, margin: 0, fit: 'shrink'
    },
    source: { file: 'slides/slide-06.js', method: 'addText' }
  });
  addMappedText(slide, map, {
    elementId: 'c1.titleEn', role: 'subtitle',
    text: 'One seat, one winner: bitmap + atomic Lua script',
    x: 0.55, y: 1.02, w: 6.6, h: 0.24,
    options: {
      fontSize: 10.5, fontFace: FONT_EN, color: THEME.secondary, margin: 0
    },
    source: { file: 'slides/slide-06.js', method: 'addText' }
  });

  // Left: the real seat-map screenshot (the dark hall panel).
  addMappedImage(slide, map, {
    elementId: 'c1.shot', role: 'image',
    path: '../assets/07-seat-locked.png',
    x: 0.55, y: 1.46, w: 4.55, h: 2.56,
    source: { file: 'slides/slide-06.js', method: 'addImage' }
  });
  addMappedShape(slide, map, pres.shapes.RECTANGLE, {
    elementId: 'c1.shotFrame', role: 'decorative',
    x: 0.55, y: 1.46, w: 4.55, h: 2.56,
    options: {
      fill: { type: 'none' },
      line: { color: TOKENS.ruleStrong, width: 1 }
    },
    source: { file: 'slides/slide-06.js', method: 'addShape' }
  });
  addMappedText(slide, map, {
    elementId: 'c1.shotCap', role: 'label',
    text: '真实选座页: 5 态图例 + 20h 锁座倒计时  /  Live seat map, 5 legend states, lock countdown',
    x: 0.55, y: 4.08, w: 4.55, h: 0.2,
    options: {
      fontSize: 8, fontFace: FONT_CN, color: TOKENS.ink3, margin: 0
    },
    source: { file: 'slides/slide-06.js', method: 'addText' }
  });

  // Right: how it works.
  addMappedShape(slide, map, pres.shapes.RECTANGLE, {
    elementId: 'c1.whyCard', role: 'card',
    x: 5.3, y: 1.46, w: 4.15, h: 1.06,
    options: {
      fill: { color: THEME.accentWash },
      line: { color: TOKENS.rule, width: 0.75 }
    },
    source: { file: 'slides/slide-06.js', method: 'addShape' }
  });
  addMappedText(slide, map, {
    elementId: 'c1.whyCn', role: 'body',
    text: '为什么不用数据库行锁? ', x: 5.46, y: 1.56, w: 3.9, h: 0.24,
    options: {
      fontSize: 11, fontFace: FONT_CN, color: THEME.accent,
      bold: true, margin: 0
    },
    source: { file: 'slides/slide-06.js', method: 'addText' }
  });
  addMappedText(slide, map, {
    elementId: 'c1.whyEn', role: 'body',
    text: 'Why not a DB row lock?', x: 5.46, y: 1.79, w: 3.9, h: 0.2,
    options: {
      fontSize: 8.5, fontFace: FONT_EN, color: THEME.secondary, margin: 0
    },
    source: { file: 'slides/slide-06.js', method: 'addText' }
  });
  addMappedText(slide, map, {
    elementId: 'c1.whyBody', role: 'body',
    text: '96 个座位若逐行 SELECT ... FOR UPDATE, 高并发下锁竞争与死锁风险陡增.位图把整场座位压成 12 字节, 一个 Lua 脚本在 Redis 内完成「校验 + 置位 + 返回冲突座位」, 全程零竞态. ',
    x: 5.46, y: 2.0, w: 3.85, h: 0.46,
    options: {
      fontSize: 8, fontFace: FONT_CN, color: THEME.primary,
      margin: 0, lineSpacingMultiple: 1.05
    },
    source: { file: 'slides/slide-06.js', method: 'addText' }
  });

  const steps = [
    ['1', 'GETBIT 校验', 'Check lock + sold bits'],
    ['2', 'SETBIT 置锁', 'Set lock bits atomically'],
    ['3', '返回 40002', 'Conflict code with seats']
  ];
  steps.forEach((s, i) => {
    const x = 5.3 + i * 1.42;
    addMappedShape(slide, map, pres.shapes.RECTANGLE, {
      elementId: `c1.step${i + 1}`, role: 'card',
      x, y: 2.66, w: 1.3, h: 0.78,
      options: {
        fill: { color: THEME.light },
        line: { color: TOKENS.rule, width: 0.75 }
      },
      source: { file: 'slides/slide-06.js', method: 'addShape' }
    });
    addMappedText(slide, map, {
      elementId: `c1.stepNo${i + 1}`, role: 'label',
      text: s[0], x: x + 0.1, y: 2.74, w: 0.3, h: 0.24,
      options: {
        fontSize: 13, fontFace: FONT_EN, color: THEME.accent,
        bold: true, margin: 0
      },
      source: { file: 'slides/slide-06.js', method: 'addText' }
    });
    addMappedText(slide, map, {
      elementId: `c1.stepCn${i + 1}`, role: 'body',
      text: s[1], x: x + 0.1, y: 3.0, w: 1.14, h: 0.2,
      options: {
        fontSize: 8.5, fontFace: FONT_CN, color: THEME.primary,
        bold: true, margin: 0
      },
      source: { file: 'slides/slide-06.js', method: 'addText' }
    });
    addMappedText(slide, map, {
      elementId: `c1.stepEn${i + 1}`, role: 'body',
      text: s[2], x: x + 0.1, y: 3.19, w: 1.14, h: 0.2,
      options: {
        fontSize: 7, fontFace: FONT_EN, color: TOKENS.ink3, margin: 0
      },
      source: { file: 'slides/slide-06.js', method: 'addText' }
    });
  });

  // Live API evidence captured from the running backend.
  addMappedShape(slide, map, pres.shapes.RECTANGLE, {
    elementId: 'c1.evidence', role: 'card',
    x: 5.3, y: 3.58, w: 4.15, h: 1.0,
    options: {
      fill: { color: TOKENS.hall },
      line: { color: TOKENS.hall, width: 0.75 }
    },
    source: { file: 'slides/slide-06.js', method: 'addShape' }
  });
  addMappedText(slide, map, {
    elementId: 'c1.evHead', role: 'label',
    text: 'GET /api/sessions/9892610004/seat-map', x: 5.44, y: 3.66, w: 3.9, h: 0.2,
    options: {
      fontSize: 8, fontFace: FONT_EN, color: 'E8E0D2', bold: true, margin: 0
    },
    source: { file: 'slides/slide-06.js', method: 'addText' }
  });
  addMappedText(slide, map, {
    elementId: 'c1.evBody', role: 'body',
    text: 'rows 8 / cols 12 / seatCount 96 / vipRowNos [7,8]\nlockBitmap AAAAAAwAAAAAAAAA\nsoldBitmap DYCAAAAABAAAAAAA\nmyLockedSeats [36,37]',
    x: 5.44, y: 3.87, w: 3.9, h: 0.64,
    options: {
      fontSize: 7.5, fontFace: 'Consolas', color: 'B9A88C',
      margin: 0, lineSpacingMultiple: 1.1
    },
    source: { file: 'slides/slide-06.js', method: 'addText' }
  });

  addPageBadge(slide, map, 'c1', 6);
}

module.exports = { createSlide };
