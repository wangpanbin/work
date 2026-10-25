// Slide 8 -- Challenge 3: delayed order closing without a message queue
const { addMappedText, addMappedShape, addMappedImage } = require('./mavis-ppt-map.cjs');
const { addPageBadge, addTitleTick, THEME, TOKENS, FONT_CN, FONT_EN } = require('./lib.js');

const STEPS = [
  ['1', '下单写 ZSet', 'ZADD seat:delay:session', 'Score = expire timestamp'],
  ['2', '定时轮询到期', 'ZRANGEBYSCORE <= now', 'Scheduled job every second'],
  ['3', '原子释放座位', 'release_seat.lua', 'Clears lock bits only'],
  ['4', '订单置为超时', 'status -> CANCELLED', 'Refund log written']
];

function createSlide(pres, theme) {
  const slide = pres.addSlide();
  slide.background = { color: THEME.bg };
  const map = pres.__mavisElementMap;
  map?.startSlide({ slideNumber: 8, slideId: 'challenge-delay', title: 'Delayed Closing' });

  addTitleTick(slide, map, pres, 'c3');
  addMappedText(slide, map, {
    elementId: 'c3.kicker', role: 'label',
    text: 'CHALLENGE 03  /  DELAYED CLOSING WITHOUT MQ', x: 0.55, y: 0.34, w: 6.5, h: 0.24,
    options: {
      fontSize: 10, fontFace: FONT_EN, color: THEME.accent,
      charSpacing: 1.6, bold: true, margin: 0
    },
    source: { file: 'slides/slide-08.js', method: 'addText' }
  });
  addMappedText(slide, map, {
    elementId: 'c3.title', role: 'title',
    text: '延迟关单: Redis ZSet 替代消息队列', x: 0.55, y: 0.58, w: 6.6, h: 0.44,
    options: {
      fontSize: 22, fontFace: FONT_CN, color: THEME.primary,
      bold: true, margin: 0, fit: 'shrink'
    },
    source: { file: 'slides/slide-08.js', method: 'addText' }
  });
  addMappedText(slide, map, {
    elementId: 'c3.titleEn', role: 'subtitle',
    text: 'A sorted set is a delay queue: score is the expire timestamp',
    x: 0.55, y: 1.02, w: 6.6, h: 0.24,
    options: {
      fontSize: 10.5, fontFace: FONT_EN, color: THEME.secondary, margin: 0
    },
    source: { file: 'slides/slide-08.js', method: 'addText' }
  });

  // Comparison: with MQ vs without MQ.
  addMappedShape(slide, map, pres.shapes.RECTANGLE, {
    elementId: 'c3.cmpL', role: 'card',
    x: 0.55, y: 1.46, w: 4.4, h: 1.06,
    options: {
      fill: { color: THEME.light },
      line: { color: TOKENS.rule, width: 0.75 }
    },
    source: { file: 'slides/slide-08.js', method: 'addShape' }
  });
  addMappedText(slide, map, {
    elementId: 'c3.cmpLT', role: 'body',
    text: '常规做法: 引入 RabbitMQ / 延迟插件', x: 0.72, y: 1.56, w: 4.1, h: 0.22,
    options: {
      fontSize: 9.5, fontFace: FONT_CN, color: TOKENS.ink3, margin: 0
    },
    source: { file: 'slides/slide-08.js', method: 'addText' }
  });
  addMappedText(slide, map, {
    elementId: 'c3.cmpLB', role: 'body',
    text: '需要额外部署, 监控与运维; 本机无 Docker, 课程环境无法稳定运行. ',
    x: 0.72, y: 1.79, w: 4.06, h: 0.4,
    options: {
      fontSize: 8, fontFace: FONT_CN, color: THEME.secondary,
      margin: 0, lineSpacingMultiple: 1.05
    },
    source: { file: 'slides/slide-08.js', method: 'addText' }
  });
  addMappedText(slide, map, {
    elementId: 'c3.cmpLE', role: 'body',
    text: 'Requires extra deployment and ops; unavailable in this environment.',
    x: 0.72, y: 2.22, w: 4.06, h: 0.24,
    options: {
      fontSize: 7, fontFace: FONT_EN, color: TOKENS.ink3,
      italic: true, margin: 0
    },
    source: { file: 'slides/slide-08.js', method: 'addText' }
  });

  addMappedShape(slide, map, pres.shapes.RECTANGLE, {
    elementId: 'c3.cmpR', role: 'card',
    x: 5.05, y: 1.46, w: 4.4, h: 1.06,
    options: {
      fill: { color: THEME.accentWash },
      line: { color: THEME.accent, width: 1 }
    },
    source: { file: 'slides/slide-08.js', method: 'addShape' }
  });
  addMappedText(slide, map, {
    elementId: 'c3.cmpRT', role: 'body',
    text: '本方案: Redis ZSet 充当延迟队列', x: 5.22, y: 1.56, w: 4.1, h: 0.22,
    options: {
      fontSize: 9.5, fontFace: FONT_CN, color: THEME.accent,
      bold: true, margin: 0
    },
    source: { file: 'slides/slide-08.js', method: 'addText' }
  });
  addMappedText(slide, map, {
    elementId: 'c3.cmpRB', role: 'body',
    text: '零新增依赖. ZSet score 即过期时间戳, 定时任务扫 ZRANGEBYSCORE 取到期订单 ',
    x: 5.22, y: 1.79, w: 4.06, h: 0.4,
    options: {
      fontSize: 8, fontFace: FONT_CN, color: THEME.primary,
      margin: 0, lineSpacingMultiple: 1.05
    },
    source: { file: 'slides/slide-08.js', method: 'addText' }
  });
  addMappedText(slide, map, {
    elementId: 'c3.cmpRE', role: 'body',
    text: 'Zero extra dependencies; score is the expiry timestamp.',
    x: 5.22, y: 2.22, w: 4.06, h: 0.24,
    options: {
      fontSize: 7, fontFace: FONT_EN, color: THEME.secondary,
      italic: true, margin: 0
    },
    source: { file: 'slides/slide-08.js', method: 'addText' }
  });

  // Four-step flow.
  STEPS.forEach((s, i) => {
    const x = 0.55 + i * 2.28;
    addMappedShape(slide, map, pres.shapes.RECTANGLE, {
      elementId: `c3.step${i + 1}`, role: 'card',
      x, y: 2.76, w: 2.12, h: 0.92,
      options: {
        fill: { color: THEME.light },
        line: { color: TOKENS.rule, width: 0.75 }
      },
      source: { file: 'slides/slide-08.js', method: 'addShape' }
    });
    addMappedText(slide, map, {
      elementId: `c3.stepNo${i + 1}`, role: 'label',
      text: s[0], x: x + 0.14, y: 2.86, w: 0.3, h: 0.26,
      options: {
        fontSize: 15, fontFace: FONT_EN, color: THEME.accent,
        bold: true, margin: 0
      },
      source: { file: 'slides/slide-08.js', method: 'addText' }
    });
    addMappedText(slide, map, {
      elementId: `c3.stepCn${i + 1}`, role: 'body',
      text: s[1], x: x + 0.5, y: 2.9, w: 1.5, h: 0.22,
      options: {
        fontSize: 9, fontFace: FONT_CN, color: THEME.primary,
        bold: true, margin: 0
      },
      source: { file: 'slides/slide-08.js', method: 'addText' }
    });
    addMappedText(slide, map, {
      elementId: `c3.stepCmd${i + 1}`, role: 'body',
      text: s[2], x: x + 0.14, y: 3.2, w: 1.86, h: 0.2,
      options: {
        fontSize: 7.5, fontFace: 'Consolas', color: THEME.accent, margin: 0
      },
      source: { file: 'slides/slide-08.js', method: 'addText' }
    });
    addMappedText(slide, map, {
      elementId: `c3.stepEn${i + 1}`, role: 'body',
      text: s[3], x: x + 0.14, y: 3.4, w: 1.86, h: 0.2,
      options: {
        fontSize: 7, fontFace: FONT_EN, color: TOKENS.ink3, margin: 0
      },
      source: { file: 'slides/slide-08.js', method: 'addText' }
    });
  });

  // Real payment page showing the live countdown.
  addMappedImage(slide, map, {
    elementId: 'c3.shot', role: 'image',
    path: '../assets/08-payment.png',
    x: 0.55, y: 3.86, w: 4.3, h: 0.94,
    source: { file: 'slides/slide-08.js', method: 'addImage' }
  });
  addMappedShape(slide, map, pres.shapes.RECTANGLE, {
    elementId: 'c3.shotFrame', role: 'decorative',
    x: 0.55, y: 3.86, w: 4.3, h: 0.94,
    options: {
      fill: { type: 'none' },
      line: { color: TOKENS.ruleStrong, width: 1 }
    },
    source: { file: 'slides/slide-08.js', method: 'addShape' }
  });
  addMappedText(slide, map, {
    elementId: 'c3.shotCap', role: 'label',
    text: '真实待支付页: 剩余 14:48 倒计时  /  Live pending-payment countdown',
    x: 5.05, y: 3.92, w: 4.4, h: 0.2,
    options: {
      fontSize: 8, fontFace: FONT_CN, color: TOKENS.ink3, margin: 0
    },
    source: { file: 'slides/slide-08.js', method: 'addText' }
  });
  addMappedText(slide, map, {
    elementId: 'c3.shotNote', role: 'body',
    text: '订单 2105525527634055170 / 4 座 / 合计 159.60 元.支付成功后确认脚本把锁定位转为已售位并清锁. ',
    x: 5.05, y: 4.14, w: 4.4, h: 0.44,
    options: {
      fontSize: 8, fontFace: FONT_CN, color: THEME.primary,
      margin: 0, lineSpacingMultiple: 1.05
    },
    source: { file: 'slides/slide-08.js', method: 'addText' }
  });
  addMappedText(slide, map, {
    elementId: 'c3.shotNoteEn', role: 'body',
    text: 'On payment, confirm_seat.lua moves lock bits to sold and clears locks.',
    x: 5.05, y: 4.6, w: 4.4, h: 0.22,
    options: {
      fontSize: 7.5, fontFace: FONT_EN, color: TOKENS.ink3,
      italic: true, margin: 0
    },
    source: { file: 'slides/slide-08.js', method: 'addText' }
  });

  addPageBadge(slide, map, 'c3', 8);
}

module.exports = { createSlide };
