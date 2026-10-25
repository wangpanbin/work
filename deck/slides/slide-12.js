// Slide 12 -- Demo 2: conversational assistant (advisory, never writes)
const { addMappedText, addMappedShape, addMappedImage } = require('./mavis-ppt-map.cjs');
const { addPageBadge, addTitleTick, THEME, TOKENS, FONT_CN, FONT_EN } = require('./lib.js');

const TOOLS = [
  ['searchMovies', '按关键词/类型/地区搜索影片'],
  ['getMovieDetail', '获取影片详情'],
  ['listSessions', '某影片某日全部场次'],
  ['getSeatSummary', '座位摘要(不返回整张位图)'],
  ['findContiguousSeats', '查找 N 个连座'],
  ['getMyOrders', '我的订单(仅当前登录用户)'],
  ['getMyOrder', '订单详情(仅当前登录用户)'],
  ['searchFaq', '搜索 FAQ 知识库(18 条)']
];

function createSlide(pres, theme) {
  const slide = pres.addSlide();
  slide.background = { color: THEME.bg };
  const map = pres.__mavisElementMap;
  map?.startSlide({ slideNumber: 12, slideId: 'demo-chat', title: 'Conversational Assistant' });

  addTitleTick(slide, map, pres, 'd2');
  addMappedText(slide, map, {
    elementId: 'd2.kicker', role: 'label',
    text: 'DEMO 02  /  CONVERSATIONAL ASSISTANT', x: 0.55, y: 0.34, w: 6.5, h: 0.24,
    options: {
      fontSize: 10, fontFace: FONT_EN, color: THEME.accent,
      charSpacing: 1.6, bold: true, margin: 0
    },
    source: { file: 'slides/slide-12.js', method: 'addText' }
  });
  addMappedText(slide, map, {
    elementId: 'd2.title', role: 'title',
    text: '对话式订票助手: 只读建议, 不代下单', x: 0.55, y: 0.58, w: 7.2, h: 0.44,
    options: {
      fontSize: 23, fontFace: FONT_CN, color: THEME.primary,
      bold: true, margin: 0, fit: 'shrink'
    },
    source: { file: 'slides/slide-12.js', method: 'addText' }
  });
  addMappedText(slide, map, {
    elementId: 'd2.titleEn', role: 'subtitle',
    text: 'Read-only by design: the assistant returns action cards, never executes writes (ADR-0002)',
    x: 0.55, y: 1.02, w: 8.4, h: 0.24,
    options: {
      fontSize: 10.5, fontFace: FONT_EN, color: THEME.secondary, margin: 0
    },
    source: { file: 'slides/slide-12.js', method: 'addText' }
  });

  // Real chat screenshot (the strongest one: action card + advisory wording).
  addMappedShape(slide, map, pres.shapes.RECTANGLE, {
    elementId: 'd2.frame', role: 'card',
    x: 0.55, y: 1.42, w: 5.35, h: 3.0,
    options: {
      fill: { color: 'FFFFFF' },
      line: { color: TOKENS.rule, width: 0.75 },
      shadow: { type: 'outer', color: '000000', blur: 8, offset: 2, angle: 90, opacity: 0.08 }
    },
    source: { file: 'slides/slide-12.js', method: 'addShape' }
  });
  addMappedImage(slide, map, {
    elementId: 'd2.img', role: 'image',
    path: '../assets/12-chat-cards.png',
    x: 0.58, y: 1.45, w: 5.29, h: 2.94,
    source: { file: 'slides/slide-12.js', method: 'addImage' }
  });
  addMappedText(slide, map, {
    elementId: 'd2.cap', role: 'label',
    text: '真实提问「帮我找3个连座」-> 助手返回 3 连座建议 + SEAT_SUGGESTION 行动卡片  /  Real query, real LLM answer',
    x: 0.55, y: 4.46, w: 5.35, h: 0.3,
    options: {
      fontSize: 7.5, fontFace: FONT_CN, color: TOKENS.ink3, margin: 0
    },
    source: { file: 'slides/slide-12.js', method: 'addText' }
  });

  // Right rail: the 8 read-only tools.
  addMappedShape(slide, map, pres.shapes.RECTANGLE, {
    elementId: 'd2.rail', role: 'card',
    x: 6.1, y: 1.42, w: 3.35, h: 2.36,
    options: {
      fill: { color: THEME.light },
      line: { color: TOKENS.rule, width: 0.75 }
    },
    source: { file: 'slides/slide-12.js', method: 'addShape' }
  });
  addMappedText(slide, map, {
    elementId: 'd2.railHead', role: 'heading',
    text: '8 个 @Tool 方法(全部只读)', x: 6.26, y: 1.5, w: 3.05, h: 0.22,
    options: {
      fontSize: 9.5, fontFace: FONT_CN, color: THEME.accent,
      bold: true, margin: 0
    },
    source: { file: 'slides/slide-12.js', method: 'addText' }
  });
  addMappedText(slide, map, {
    elementId: 'd2.railEn', role: 'body',
    text: 'All 8 tools are read-only; no write tool is registered',
    x: 6.26, y: 1.71, w: 3.05, h: 0.2,
    options: {
      fontSize: 7.5, fontFace: FONT_EN, color: TOKENS.ink3, margin: 0
    },
    source: { file: 'slides/slide-12.js', method: 'addText' }
  });
  TOOLS.forEach((t, i) => {
    const col = i < 4 ? 0 : 1;
    const row = i % 4;
    const x = 6.26 + col * 1.6;
    const y = 1.98 + row * 0.42;
    addMappedText(slide, map, {
      elementId: `d2.tool${i + 1}`, role: 'body',
      text: t[0], x, y, w: 1.55, h: 0.18,
      options: {
        fontSize: 6.5, fontFace: FONT_EN, color: THEME.accent,
        bold: true, margin: 0
      },
      source: { file: 'slides/slide-12.js', method: 'addText' }
    });
    addMappedText(slide, map, {
      elementId: `d2.toolDesc${i + 1}`, role: 'body',
      text: t[1], x, y: y + 0.17, w: 1.55, h: 0.2,
      options: {
        fontSize: 6.5, fontFace: FONT_CN, color: THEME.primary, margin: 0
      },
      source: { file: 'slides/slide-12.js', method: 'addText' }
    });
  });

  // Three ADR-style decision cards.
  const adrs = [
    ['ADR-0001', 'LangChain4j 嵌入 cinema-server, 进程内直调领域 Service', 'In-process LLM calls, no separate service'],
    ['ADR-0002', '助手只产出行动卡片(建议), 不注册任何写工具', 'Action cards are suggestions, not execution'],
    ['ADR-0003', '不引入 RAG / Embedding / 向量库', 'No RAG, no embeddings, no vector store']
  ];
  adrs.forEach((a, i) => {
    const y = 3.9 + i * 0.44;
    addMappedShape(slide, map, pres.shapes.RECTANGLE, {
      elementId: `d2.adr${i + 1}`, role: 'card',
      x: 6.1, y, w: 3.35, h: 0.38,
      options: {
        fill: { color: i === 1 ? THEME.accentWash : THEME.light },
        line: { color: i === 1 ? THEME.accent : TOKENS.rule, width: 0.75 }
      },
      source: { file: 'slides/slide-12.js', method: 'addShape' }
    });
    addMappedText(slide, map, {
      elementId: `d2.adrId${i + 1}`, role: 'label',
      text: a[0], x: 6.2, y: y + 0.04, w: 0.72, h: 0.18,
      options: {
        fontSize: 7, fontFace: FONT_EN, color: THEME.accent,
        bold: true, margin: 0
      },
      source: { file: 'slides/slide-12.js', method: 'addText' }
    });
    addMappedText(slide, map, {
      elementId: `d2.adrCn${i + 1}`, role: 'body',
      text: a[1], x: 6.2, y: y + 0.2, w: 3.15, h: 0.18,
      options: {
        fontSize: 7, fontFace: FONT_CN, color: THEME.primary, margin: 0
      },
      source: { file: 'slides/slide-12.js', method: 'addText' }
    });
  });

  addPageBadge(slide, map, 'd2', 12);
}

module.exports = { createSlide };
