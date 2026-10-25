// Slide 9 -- Challenge 4: real-time seat sync over WebSocket
const { addMappedText, addMappedShape, addMappedImage } = require('./mavis-ppt-map.cjs');
const { addPageBadge, addTitleTick, THEME, TOKENS, FONT_CN, FONT_EN } = require('./lib.js');

function createSlide(pres, theme) {
  const slide = pres.addSlide();
  slide.background = { color: THEME.bg };
  const map = pres.__mavisElementMap;
  map?.startSlide({ slideNumber: 9, slideId: 'challenge-ws', title: 'Real-Time Seat Sync' });

  addTitleTick(slide, map, pres, 'c4');
  addMappedText(slide, map, {
    elementId: 'c4.kicker', role: 'label',
    text: 'CHALLENGE 04  /  REAL-TIME SYNC', x: 0.55, y: 0.34, w: 6.5, h: 0.24,
    options: {
      fontSize: 10, fontFace: FONT_EN, color: THEME.accent,
      charSpacing: 1.6, bold: true, margin: 0
    },
    source: { file: 'slides/slide-09.js', method: 'addText' }
  });
  addMappedText(slide, map, {
    elementId: 'c4.title', role: 'title',
    text: '实时座位同步: WebSocket 推送', x: 0.55, y: 0.58, w: 6.6, h: 0.44,
    options: {
      fontSize: 24, fontFace: FONT_CN, color: THEME.primary,
      bold: true, margin: 0
    },
    source: { file: 'slides/slide-09.js', method: 'addText' }
  });
  addMappedText(slide, map, {
    elementId: 'c4.titleEn', role: 'subtitle',
    text: 'Two users, one seat map, zero manual refresh',
    x: 0.55, y: 1.02, w: 6.6, h: 0.24,
    options: {
      fontSize: 10.5, fontFace: FONT_EN, color: THEME.secondary, margin: 0
    },
    source: { file: 'slides/slide-09.js', method: 'addText' }
  });

  // Real admin event stream showing a live LOCK event.
  addMappedImage(slide, map, {
    elementId: 'c4.live', role: 'image',
    path: '../assets/15-live.png',
    x: 0.55, y: 1.46, w: 4.55, h: 2.56,
    source: { file: 'slides/slide-09.js', method: 'addImage' }
  });
  addMappedShape(slide, map, pres.shapes.RECTANGLE, {
    elementId: 'c4.liveFrame', role: 'decorative',
    x: 0.55, y: 1.46, w: 4.55, h: 2.56,
    options: {
      fill: { type: 'none' },
      line: { color: TOKENS.ruleStrong, width: 1 }
    },
    source: { file: 'slides/slide-09.js', method: 'addShape' }
  });
  addMappedText(slide, map, {
    elementId: 'c4.liveCap', role: 'label',
    text: '管理端实时事件流: user2 锁座事件被即时捕获  /  Admin live event stream captured a real LOCK event',
    x: 0.55, y: 4.08, w: 4.55, h: 0.2,
    options: {
      fontSize: 8, fontFace: FONT_CN, color: TOKENS.ink3, margin: 0
    },
    source: { file: 'slides/slide-09.js', method: 'addText' }
  });

  const facts = [
    ['/ws/seat/{sessionId}', '公开通道, 进入选座页即订阅', 'Public channel, auto-subscribed on seat page'],
    ['15s 心跳 + 自动重连', '断线后指数退避重连, 恢复即全量刷新', 'Heartbeat with backoff, then full refresh'],
    ['增量事件 reducer', 'seatEventReducer 把推送合并进本地状态', 'Reducer merges pushes into local state'],
    ['管理端 /ws/admin', '汇总锁座, 支付, 退款事件流', 'Aggregates lock / pay / refund events']
  ];
  facts.forEach((f, i) => {
    const y = 1.46 + i * 0.66;
    addMappedShape(slide, map, pres.shapes.RECTANGLE, {
      elementId: `c4.fact${i + 1}`, role: 'card',
      x: 5.3, y, w: 4.15, h: 0.56,
      options: {
        fill: { color: THEME.light },
        line: { color: TOKENS.rule, width: 0.75 }
      },
      source: { file: 'slides/slide-09.js', method: 'addShape' }
    });
    addMappedShape(slide, map, pres.shapes.RECTANGLE, {
      elementId: `c4.factBar${i + 1}`, role: 'decorative',
      x: 5.3, y, w: 0.045, h: 0.56,
      options: { fill: { color: THEME.ok }, line: { color: THEME.ok, width: 0.5 } },
      source: { file: 'slides/slide-09.js', method: 'addShape' }
    });
    addMappedText(slide, map, {
      elementId: `c4.factCn${i + 1}`, role: 'body',
      text: f[1], x: 5.46, y: y + 0.07, w: 3.9, h: 0.22,
      options: {
        fontSize: 9.5, fontFace: FONT_CN, color: THEME.primary,
        bold: true, margin: 0
      },
      source: { file: 'slides/slide-09.js', method: 'addText' }
    });
    addMappedText(slide, map, {
      elementId: `c4.factEn${i + 1}`, role: 'body',
      text: f[2], x: 5.46, y: y + 0.3, w: 3.9, h: 0.2,
      options: {
        fontSize: 7.5, fontFace: FONT_EN, color: TOKENS.ink3, margin: 0
      },
      source: { file: 'slides/slide-09.js', method: 'addText' }
    });
  });

  addMappedText(slide, map, {
    elementId: 'c4.proof', role: 'body',
    text: '事件流证据: 13:13:04 LOCK  用户 2093944056670674945  锁座 session=9892610004  座位 24,25; 同屏「今日订单」由 24 跳至 25. ',
    x: 5.3, y: 4.16, w: 4.15, h: 0.42,
    options: {
      fontSize: 7.5, fontFace: FONT_CN, color: THEME.primary,
      margin: 0, lineSpacingMultiple: 1.05
    },
    source: { file: 'slides/slide-09.js', method: 'addText' }
  });
  addMappedText(slide, map, {
    elementId: 'c4.proofEn', role: 'body',
    text: 'Evidence: order count ticked 24 -> 25 without a page refresh.',
    x: 5.3, y: 4.6, w: 4.15, h: 0.22,
    options: {
      fontSize: 7.5, fontFace: FONT_EN, color: TOKENS.ink3,
      italic: true, margin: 0
    },
    source: { file: 'slides/slide-09.js', method: 'addText' }
  });

  addPageBadge(slide, map, 'c4', 9);
}

module.exports = { createSlide };
