// Slide 14 -- Section divider 03
const { addMappedText, addMappedShape } = require('./mavis-ppt-map.cjs');
const { addPageBadge, THEME, TOKENS, FONT_CN, FONT_EN } = require('./lib.js');

function createSlide(pres, theme) {
  const slide = pres.addSlide();
  slide.background = { color: TOKENS.hall };
  const map = pres.__mavisElementMap;
  map?.startSlide({ slideNumber: 14, slideId: 'section-03', title: 'Quality Assurance' });

  addMappedShape(slide, map, pres.shapes.RECTANGLE, {
    elementId: 'sec03.block', role: 'decorative',
    x: 0, y: 0, w: 3.1, h: 5.625,
    options: { fill: { color: THEME.accent }, line: { color: THEME.accent, width: 0.5 } },
    source: { file: 'slides/slide-14.js', method: 'addShape' }
  });

  addMappedText(slide, map, {
    elementId: 'sec03.num', role: 'value',
    text: '03', x: 0.7, y: 1.9, w: 2.2, h: 1.4,
    options: {
      fontSize: 78, fontFace: FONT_EN, color: 'FFFFFF',
      bold: true, margin: 0
    },
    source: { file: 'slides/slide-14.js', method: 'addText' }
  });

  addMappedText(slide, map, {
    elementId: 'sec03.title', role: 'title',
    text: '质量保障与总结', x: 3.75, y: 2.12, w: 5.6, h: 0.56,
    options: {
      fontSize: 32, fontFace: FONT_CN, color: TOKENS.hallInk,
      bold: true, margin: 0
    },
    source: { file: 'slides/slide-14.js', method: 'addText' }
  });
  addMappedText(slide, map, {
    elementId: 'sec03.titleEn', role: 'subtitle',
    text: 'Quality Assurance and Summary', x: 3.75, y: 2.72, w: 5.6, h: 0.3,
    options: {
      fontSize: 14, fontFace: FONT_EN, color: 'B9A88C', margin: 0
    },
    source: { file: 'slides/slide-14.js', method: 'addText' }
  });
  addMappedShape(slide, map, pres.shapes.RECTANGLE, {
    elementId: 'sec03.rule', role: 'decorative',
    x: 3.75, y: 3.18, w: 1.1, h: 0.035,
    options: { fill: { color: THEME.accent }, line: { color: THEME.accent, width: 0.5 } },
    source: { file: 'slides/slide-14.js', method: 'addShape' }
  });

  const items = [
    ['142 个后端单测全部通过', '142 backend unit tests, all green'],
    ['137 个前端用例 14 个文件', '137 frontend cases across 14 files'],
    ['端到端实测发现并修复 14 个缺陷', 'E2E run surfaced and fixed 14 defects'],
    ['已知限制与后续演进方向', 'Known limits and next steps']
  ];
  items.forEach((t, i) => {
    addMappedText(slide, map, {
      elementId: `sec03.item${i + 1}`, role: 'body',
      text: t[0], x: 3.75, y: 3.4 + i * 0.42, w: 5.6, h: 0.24,
      options: {
        fontSize: 11, fontFace: FONT_CN, color: TOKENS.hallInk, margin: 0
      },
      source: { file: 'slides/slide-14.js', method: 'addText' }
    });
    addMappedText(slide, map, {
      elementId: `sec03.itemEn${i + 1}`, role: 'body',
      text: t[1], x: 3.75, y: 3.61 + i * 0.42, w: 5.6, h: 0.2,
      options: {
        fontSize: 8, fontFace: FONT_EN, color: '9C9284',
        italic: true, margin: 0
      },
      source: { file: 'slides/slide-14.js', method: 'addText' }
    });
  });

  addPageBadge(slide, map, 'sec03', 14);
}

module.exports = { createSlide };
