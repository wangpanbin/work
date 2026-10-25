// Slide 10 -- Section divider 02 (live demo)
const { addMappedText, addMappedShape, addMappedImage } = require('./mavis-ppt-map.cjs');
const { addPageBadge, THEME, TOKENS, FONT_CN, FONT_EN } = require('./lib.js');

function createSlide(pres, theme) {
  const slide = pres.addSlide();
  slide.background = { color: TOKENS.hall };
  const map = pres.__mavisElementMap;
  map?.startSlide({ slideNumber: 10, slideId: 'section-02', title: 'Live Demo' });

  addMappedShape(slide, map, pres.shapes.RECTANGLE, {
    elementId: 'sec02.block', role: 'decorative',
    x: 0, y: 0, w: 3.1, h: 5.625,
    options: { fill: { color: THEME.accent }, line: { color: THEME.accent, width: 0.5 } },
    source: { file: 'slides/slide-10.js', method: 'addShape' }
  });

  addMappedText(slide, map, {
    elementId: 'sec02.num', role: 'value',
    text: '02', x: 0.7, y: 1.9, w: 2.2, h: 1.4,
    options: {
      fontSize: 78, fontFace: FONT_EN, color: 'FFFFFF',
      bold: true, margin: 0
    },
    source: { file: 'slides/slide-10.js', method: 'addText' }
  });

  addMappedText(slide, map, {
    elementId: 'sec02.title', role: 'title',
    text: '真实运行演示', x: 3.75, y: 1.72, w: 5.6, h: 0.56,
    options: {
      fontSize: 32, fontFace: FONT_CN, color: TOKENS.hallInk,
      bold: true, margin: 0
    },
    source: { file: 'slides/slide-10.js', method: 'addText' }
  });
  addMappedText(slide, map, {
    elementId: 'sec02.titleEn', role: 'subtitle',
    text: 'Live Demo - captured from the running stack', x: 3.75, y: 2.32, w: 5.6, h: 0.3,
    options: {
      fontSize: 13, fontFace: FONT_EN, color: 'B9A88C', margin: 0
    },
    source: { file: 'slides/slide-10.js', method: 'addText' }
  });
  addMappedShape(slide, map, pres.shapes.RECTANGLE, {
    elementId: 'sec02.rule', role: 'decorative',
    x: 3.75, y: 2.78, w: 1.1, h: 0.035,
    options: { fill: { color: THEME.accent }, line: { color: THEME.accent, width: 0.5 } },
    source: { file: 'slides/slide-10.js', method: 'addShape' }
  });

  const items = [
    ['用户端主流程', '选座 -> 锁座 -> 支付 -> 订单', 'User flow: seat, lock, pay, order'],
    ['对话式订票助手', '只读工具 + 行动卡片', 'Read-only tools plus action cards'],
    ['管理端经营与实时', '看板 + 事件流 + 导出', 'Dashboard, event stream, export']
  ];
  items.forEach((it, i) => {
    const y = 3.02 + i * 0.62;
    addMappedText(slide, map, {
      elementId: `sec02.itemCn${i + 1}`, role: 'body',
      text: it[0], x: 3.75, y, w: 5.6, h: 0.24,
      options: {
        fontSize: 12, fontFace: FONT_CN, color: TOKENS.hallInk,
        bold: true, margin: 0
      },
      source: { file: 'slides/slide-10.js', method: 'addText' }
    });
    addMappedText(slide, map, {
      elementId: `sec02.itemDesc${i + 1}`, role: 'body',
      text: it[1], x: 3.75, y: y + 0.24, w: 2.7, h: 0.2,
      options: {
        fontSize: 8.5, fontFace: FONT_CN, color: '9C9284', margin: 0
      },
      source: { file: 'slides/slide-10.js', method: 'addText' }
    });
    addMappedText(slide, map, {
      elementId: `sec02.itemEn${i + 1}`, role: 'body',
      text: it[2], x: 6.4, y: y + 0.24, w: 3.0, h: 0.2,
      options: {
        fontSize: 8, fontFace: FONT_EN, color: '9C9284',
        italic: true, align: 'right', margin: 0
      },
      source: { file: 'slides/slide-10.js', method: 'addText' }
    });
  });

  // Small real screenshot to signal this section is evidence, not mockup.
  addMappedImage(slide, map, {
    elementId: 'sec02.shot', role: 'image',
    path: '../assets/09-paid.png',
    x: 7.35, y: 0.42, w: 2.35, h: 0.9,
    source: { file: 'slides/slide-10.js', method: 'addImage' }
  });
  addMappedShape(slide, map, pres.shapes.RECTANGLE, {
    elementId: 'sec02.shotFrame', role: 'decorative',
    x: 7.35, y: 0.42, w: 2.35, h: 0.9,
    options: {
      fill: { type: 'none' },
      line: { color: 'B9A88C', width: 1 }
    },
    source: { file: 'slides/slide-10.js', method: 'addShape' }
  });

  addPageBadge(slide, map, 'sec02', 10);
}

module.exports = { createSlide };
