// Slide 2 -- Table of contents
const { addMappedText, addMappedShape } = require('./mavis-ppt-map.cjs');
const { addPageBadge, THEME, TOKENS, FONT_CN, FONT_EN } = require('./lib.js');

const SECTIONS = [
  ['01', '项目背景与目标', 'Background and Goals'],
  ['02', '系统架构与技术栈', 'Architecture and Stack'],
  ['03', '四大技术难点', 'Four Core Challenges'],
  ['04', '真实运行演示', 'Live Demo'],
  ['05', '质量保障与总结', 'Quality Assurance and Summary']
];

function createSlide(pres, theme) {
  const slide = pres.addSlide();
  slide.background = { color: THEME.bg };
  const map = pres.__mavisElementMap;
  map?.startSlide({ slideNumber: 2, slideId: 'toc', title: 'Agenda' });

  addMappedText(slide, map, {
    elementId: 'toc.kicker', role: 'label',
    text: 'AGENDA', x: 0.55, y: 0.44, w: 5, h: 0.24,
    options: {
      fontSize: 10, fontFace: FONT_EN, color: THEME.accent,
      charSpacing: 2.5, bold: true, margin: 0
    },
    source: { file: 'slides/slide-02.js', method: 'addText' }
  });
  addMappedText(slide, map, {
    elementId: 'toc.title', role: 'title',
    text: '目录', x: 0.55, y: 0.68, w: 5, h: 0.5,
    options: {
      fontSize: 27, fontFace: FONT_CN, color: THEME.primary,
      bold: true, margin: 0
    },
    source: { file: 'slides/slide-02.js', method: 'addText' }
  });
  addMappedText(slide, map, {
    elementId: 'toc.titleEn', role: 'subtitle',
    text: 'Contents', x: 0.55, y: 1.16, w: 5, h: 0.24,
    options: {
      fontSize: 11, fontFace: FONT_EN, color: THEME.secondary, margin: 0
    },
    source: { file: 'slides/slide-02.js', method: 'addText' }
  });

  SECTIONS.forEach((s, i) => {
    const y = 1.72 + i * 0.72;
    addMappedShape(slide, map, pres.shapes.RECTANGLE, {
      elementId: `toc.block${i + 1}`, role: 'card',
      x: 0.55, y, w: 8.9, h: 0.6,
      options: {
        fill: { color: THEME.light },
        line: { color: TOKENS.rule, width: 0.75 }
      },
      source: { file: 'slides/slide-02.js', method: 'addShape' }
    });
    addMappedShape(slide, map, pres.shapes.RECTANGLE, {
      elementId: `toc.num${i + 1}`, role: 'decorative',
      x: 0.55, y, w: 0.05, h: 0.6,
      options: { fill: { color: THEME.accent }, line: { color: THEME.accent, width: 0.5 } },
      source: { file: 'slides/slide-02.js', method: 'addShape' }
    });
    addMappedText(slide, map, {
      elementId: `toc.no${i + 1}`, role: 'value',
      text: s[0], x: 0.72, y: y + 0.14, w: 0.6, h: 0.34,
      options: {
        fontSize: 19, fontFace: FONT_EN, color: THEME.accent,
        bold: true, margin: 0
      },
      source: { file: 'slides/slide-02.js', method: 'addText' }
    });
    addMappedText(slide, map, {
      elementId: `toc.cn${i + 1}`, role: 'body',
      text: s[1], x: 1.42, y: y + 0.09, w: 3.6, h: 0.24,
      options: {
        fontSize: 13, fontFace: FONT_CN, color: THEME.primary,
        bold: true, margin: 0
      },
      source: { file: 'slides/slide-02.js', method: 'addText' }
    });
    addMappedText(slide, map, {
      elementId: `toc.en${i + 1}`, role: 'body',
      text: s[2], x: 1.42, y: y + 0.33, w: 5.4, h: 0.22,
      options: {
        fontSize: 9.5, fontFace: FONT_EN, color: THEME.secondary, margin: 0
      },
      source: { file: 'slides/slide-02.js', method: 'addText' }
    });
  });

  addPageBadge(slide, map, 'toc', 2);
}

module.exports = { createSlide };
