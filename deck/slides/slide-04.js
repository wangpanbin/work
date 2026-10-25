// Slide 4 -- Architecture
const { addMappedText, addMappedShape } = require('./mavis-ppt-map.cjs');
const { addPageBadge, addTitleTick, THEME, TOKENS, FONT_CN, FONT_EN } = require('./lib.js');

const LAYERS = [
  {
    cn: '前端展现层', en: 'Vue 3 Client',
    tech: 'Vite + TypeScript + Element Plus + Pinia + vue-i18n',
    fill: 'D8E4DC'
  },
  {
    cn: '接口与横切层', en: 'REST / AOP Layer',
    tech: 'Controllers + @RateLimit + @Idempotent + @Valid + GlobalExceptionHandler',
    fill: 'E4DAC4'
  },
  {
    cn: '领域服务层', en: 'Domain Services',
    tech: 'OrderLock / OrderPay / OrderCancel / Seat / Session / Chat / Admin',
    fill: 'E8D2CE'
  },
  {
    cn: '数据与中间件', en: 'Data & Middleware',
    tech: 'MySQL 8 (MyBatis-Plus) + Redis 8 (bitmap / Lua / ZSet delay queue)',
    fill: 'DCD3C4'
  }
];

function createSlide(pres, theme) {
  const slide = pres.addSlide();
  slide.background = { color: THEME.bg };
  const map = pres.__mavisElementMap;
  map?.startSlide({ slideNumber: 4, slideId: 'architecture', title: 'Architecture' });

  addTitleTick(slide, map, pres, 'arch');
  addMappedText(slide, map, {
    elementId: 'arch.kicker', role: 'label',
    text: '02  ARCHITECTURE AND STACK', x: 0.55, y: 0.34, w: 6, h: 0.24,
    options: {
      fontSize: 10, fontFace: FONT_EN, color: THEME.accent,
      charSpacing: 2, bold: true, margin: 0
    },
    source: { file: 'slides/slide-04.js', method: 'addText' }
  });
  addMappedText(slide, map, {
    elementId: 'arch.title', role: 'title',
    text: '系统架构与技术栈', x: 0.55, y: 0.58, w: 6, h: 0.44,
    options: {
      fontSize: 24, fontFace: FONT_CN, color: THEME.primary,
      bold: true, margin: 0
    },
    source: { file: 'slides/slide-04.js', method: 'addText' }
  });
  addMappedText(slide, map, {
    elementId: 'arch.titleEn', role: 'subtitle',
    text: 'Four layers, one request path: Vue 3 -> Spring Boot 3.5 -> Redis / MySQL',
    x: 0.55, y: 1.02, w: 7.4, h: 0.24,
    options: {
      fontSize: 10.5, fontFace: FONT_EN, color: THEME.secondary, margin: 0
    },
    source: { file: 'slides/slide-04.js', method: 'addText' }
  });

  LAYERS.forEach((L, i) => {
    const y = 1.52 + i * 0.82;
    addMappedShape(slide, map, pres.shapes.RECTANGLE, {
      elementId: `arch.layer${i + 1}`, role: 'card',
      x: 0.55, y, w: 6.15, h: 0.68,
      options: {
        fill: { color: L.fill },
        line: { color: TOKENS.ruleStrong, width: 0.75 }
      },
      source: { file: 'slides/slide-04.js', method: 'addShape' }
    });
    addMappedShape(slide, map, pres.shapes.RECTANGLE, {
      elementId: `arch.tag${i + 1}`, role: 'decorative',
      x: 0.7, y: y + 0.12, w: 0.5, h: 0.44,
      options: {
        fill: { color: THEME.accent }
      },
      source: { file: 'slides/slide-04.js', method: 'addShape' }
    });
    addMappedText(slide, map, {
      elementId: `arch.tagTxt${i + 1}`, role: 'label',
      text: `L${i + 1}`, x: 0.7, y: y + 0.23, w: 0.5, h: 0.24,
      options: {
        fontSize: 11, fontFace: FONT_EN, color: 'FFFFFF',
        bold: true, align: 'center', margin: 0
      },
      source: { file: 'slides/slide-04.js', method: 'addText' }
    });
    addMappedText(slide, map, {
      elementId: `arch.layerCn${i + 1}`, role: 'body',
      text: L.cn, x: 1.34, y: y + 0.1, w: 2.2, h: 0.24,
      options: {
        fontSize: 11.5, fontFace: FONT_CN, color: THEME.primary,
        bold: true, margin: 0
      },
      source: { file: 'slides/slide-04.js', method: 'addText' }
    });
    addMappedText(slide, map, {
      elementId: `arch.layerEn${i + 1}`, role: 'body',
      text: L.en, x: 1.34, y: y + 0.33, w: 2.2, h: 0.2,
      options: {
        fontSize: 8, fontFace: FONT_EN, color: TOKENS.ink3, margin: 0
      },
      source: { file: 'slides/slide-04.js', method: 'addText' }
    });
    addMappedText(slide, map, {
      elementId: `arch.tech${i + 1}`, role: 'body',
      text: L.tech, x: 3.6, y: y + 0.22, w: 3.0, h: 0.34,
      options: {
        fontSize: 8, fontFace: FONT_EN, color: THEME.primary,
        margin: 0, valign: 'middle'
      },
      source: { file: 'slides/slide-04.js', method: 'addText' }
    });
  });

  // Right rail: key technology facts.
  addMappedShape(slide, map, pres.shapes.RECTANGLE, {
    elementId: 'arch.rail', role: 'card',
    x: 6.9, y: 1.52, w: 2.55, h: 3.14,
    options: {
      fill: { color: THEME.light },
      line: { color: TOKENS.rule, width: 0.75 }
    },
    source: { file: 'slides/slide-04.js', method: 'addShape' }
  });
  addMappedText(slide, map, {
    elementId: 'arch.railHead', role: 'heading',
    text: '技术选型要点', x: 7.08, y: 1.68, w: 2.2, h: 0.22,
    options: {
      fontSize: 10.5, fontFace: FONT_CN, color: THEME.accent,
      bold: true, margin: 0
    },
    source: { file: 'slides/slide-04.js', method: 'addText' }
  });
  addMappedText(slide, map, {
    elementId: 'arch.railEn', role: 'body',
    text: 'Key stack decisions', x: 7.08, y: 1.89, w: 2.2, h: 0.2,
    options: {
      fontSize: 8, fontFace: FONT_EN, color: TOKENS.ink3, margin: 0
    },
    source: { file: 'slides/slide-04.js', method: 'addText' }
  });

  const notes = [
    ['JDK 21 + Spring Boot 3.5', 'Baseline runtime'],
    ['位图取代 96 次读写', 'Bitmap instead of 96 row ops'],
    ['Lua 保证原子性', 'Lua for atomicity'],
    ['ZSet 代替 MQ 延迟队列', 'ZSet delay queue, no MQ'],
    ['LangChain4j 进程内直调', 'In-process LLM calls'],
    ['无 RAG / 无向量库', 'No RAG, no vector DB']
  ];
  notes.forEach((n, i) => {
    const y = 2.2 + i * 0.4;
    addMappedText(slide, map, {
      elementId: `arch.note${i + 1}`, role: 'body',
      text: n[0], x: 7.08, y, w: 2.25, h: 0.2,
      options: {
        fontSize: 8.5, fontFace: FONT_CN, color: THEME.primary, margin: 0
      },
      source: { file: 'slides/slide-04.js', method: 'addText' }
    });
    addMappedText(slide, map, {
      elementId: `arch.noteEn${i + 1}`, role: 'body',
      text: n[1], x: 7.08, y: y + 0.19, w: 2.25, h: 0.18,
      options: {
        fontSize: 7.5, fontFace: FONT_EN, color: TOKENS.ink3, margin: 0
      },
      source: { file: 'slides/slide-04.js', method: 'addText' }
    });
  });

  addPageBadge(slide, map, 'arch', 4);
}

module.exports = { createSlide };
