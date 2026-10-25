// Slide 11 -- Demo 1: user booking flow (4 real screenshots)
const { addMappedText, addMappedShape, addMappedImage } = require('./mavis-ppt-map.cjs');
const { addPageBadge, addTitleTick, THEME, TOKENS, FONT_CN, FONT_EN } = require('./lib.js');

const SHOTS = [
  {
    id: 'd1a', path: '../assets/05-movie-detail.png',
    capCn: '1.影片详情与场次选择', capEn: 'Movie detail and session list'
  },
  {
    id: 'd1b', path: '../assets/07-seat-locked.png',
    capCn: '2.选座并确认锁座(4 座)', capEn: 'Seat selection and lock (4 seats)'
  },
  {
    id: 'd1c', path: '../assets/09-paid.png',
    capCn: '3.支付成功生成电子票', capEn: 'Payment succeeded, e-ticket issued'
  },
  {
    id: 'd1d', path: '../assets/10-orders.png',
    capCn: '4.订单列表 5 态可见', capEn: 'Order list across 5 states'
  }
];

function createSlide(pres, theme) {
  const slide = pres.addSlide();
  slide.background = { color: THEME.bg };
  const map = pres.__mavisElementMap;
  map?.startSlide({ slideNumber: 11, slideId: 'demo-flow', title: 'User Booking Flow' });

  addTitleTick(slide, map, pres, 'd1');
  addMappedText(slide, map, {
    elementId: 'd1.kicker', role: 'label',
    text: 'DEMO 01  /  END-TO-END BOOKING', x: 0.55, y: 0.34, w: 6.5, h: 0.24,
    options: {
      fontSize: 10, fontFace: FONT_EN, color: THEME.accent,
      charSpacing: 1.6, bold: true, margin: 0
    },
    source: { file: 'slides/slide-11.js', method: 'addText' }
  });
  addMappedText(slide, map, {
    elementId: 'd1.title', role: 'title',
    text: '用户端主流程: 选座 -> 锁座 -> 支付', x: 0.55, y: 0.58, w: 7.2, h: 0.44,
    options: {
      fontSize: 23, fontFace: FONT_CN, color: THEME.primary,
      bold: true, margin: 0, fit: 'shrink'
    },
    source: { file: 'slides/slide-11.js', method: 'addText' }
  });
  addMappedText(slide, map, {
    elementId: 'd1.titleEn', role: 'subtitle',
    text: 'One complete transaction, every frame captured from the live system',
    x: 0.55, y: 1.02, w: 7.2, h: 0.24,
    options: {
      fontSize: 10.5, fontFace: FONT_EN, color: THEME.secondary, margin: 0
    },
    source: { file: 'slides/slide-11.js', method: 'addText' }
  });

  // 2x2 grid of real screenshots.
  const w = 4.35;
  const h = 1.55;
  const pos = [
    { x: 0.55, y: 1.42 },
    { x: 5.1, y: 1.42 },
    { x: 0.55, y: 3.24 },
    { x: 5.1, y: 3.24 }
  ];
  SHOTS.forEach((s, i) => {
    const p = pos[i];
    addMappedShape(slide, map, pres.shapes.RECTANGLE, {
      elementId: `${s.id}.frame`, role: 'card',
      x: p.x, y: p.y, w, h,
      options: {
        fill: { color: 'FFFFFF' },
        line: { color: TOKENS.rule, width: 0.75 },
        shadow: {
          type: 'outer', color: '000000', blur: 6, offset: 1, angle: 90, opacity: 0.08
        }
      },
      source: { file: 'slides/slide-11.js', method: 'addShape' }
    });
    addMappedImage(slide, map, {
      elementId: `${s.id}.img`, role: 'image',
      path: s.path,
      x: p.x + 0.03, y: p.y + 0.03, w: w - 0.06, h: h - 0.06,
      source: { file: 'slides/slide-11.js', method: 'addImage' }
    });
    addMappedText(slide, map, {
      elementId: `${s.id}.cap`, role: 'label',
      text: `${s.capCn}   |   ${s.capEn}`,
      x: p.x, y: p.y + h + 0.03, w, h: 0.18,
      options: {
        fontSize: 7.5, fontFace: FONT_CN, color: THEME.primary, margin: 0
      },
      source: { file: 'slides/slide-11.js', method: 'addText' }
    });
  });

  addMappedText(slide, map, {
    elementId: 'd1.foot', role: 'body',
    text: '实测数据: 流浪地球2 / 1号激光厅 / 2026-10-02 10:00 / 座位 1排5座 1排6座 2排5座 5排6座 / 订单 2105525527634055170 / 合计 159.60 元',
    x: 0.55, y: 5.09, w: 8.6, h: 0.22,
    options: {
      fontSize: 7.5, fontFace: FONT_CN, color: TOKENS.ink3, margin: 0
    },
    source: { file: 'slides/slide-11.js', method: 'addText' }
  });

  addPageBadge(slide, map, 'd1', 11);
}

module.exports = { createSlide };
