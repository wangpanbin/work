// Slide 13 -- Demo 3: admin console + bilingual i18n
const { addMappedText, addMappedShape, addMappedImage } = require('./mavis-ppt-map.cjs');
const { addPageBadge, addTitleTick, THEME, TOKENS, FONT_CN, FONT_EN } = require('./lib.js');

function createSlide(pres, theme) {
  const slide = pres.addSlide();
  slide.background = { color: THEME.bg };
  const map = pres.__mavisElementMap;
  map?.startSlide({ slideNumber: 13, slideId: 'demo-admin', title: 'Admin Console and i18n' });

  addTitleTick(slide, map, pres, 'd3');
  addMappedText(slide, map, {
    elementId: 'd3.kicker', role: 'label',
    text: 'DEMO 03  /  ADMIN CONSOLE AND BILINGUAL UI', x: 0.55, y: 0.34, w: 6.5, h: 0.24,
    options: {
      fontSize: 10, fontFace: FONT_EN, color: THEME.accent,
      charSpacing: 1.6, bold: true, margin: 0
    },
    source: { file: 'slides/slide-13.js', method: 'addText' }
  });
  addMappedText(slide, map, {
    elementId: 'd3.title', role: 'title',
    text: '管理端经营看板与中英双语运行时切换', x: 0.55, y: 0.58, w: 7.6, h: 0.44,
    options: {
      fontSize: 22, fontFace: FONT_CN, color: THEME.primary,
      bold: true, margin: 0, fit: 'shrink'
    },
    source: { file: 'slides/slide-13.js', method: 'addText' }
  });
  addMappedText(slide, map, {
    elementId: 'd3.titleEn', role: 'subtitle',
    text: 'Revenue dashboard, Excel export, and vue-i18n runtime language switching',
    x: 0.55, y: 1.02, w: 7.6, h: 0.24,
    options: {
      fontSize: 10.5, fontFace: FONT_EN, color: THEME.secondary, margin: 0
    },
    source: { file: 'slides/slide-13.js', method: 'addText' }
  });

  // Left: revenue dashboard (admin cool-gray variant).
  addMappedShape(slide, map, pres.shapes.RECTANGLE, {
    elementId: 'd3.revFrame', role: 'card',
    x: 0.55, y: 1.4, w: 4.55, h: 2.56,
    options: {
      fill: { color: 'FFFFFF' },
      line: { color: TOKENS.rule, width: 0.75 },
      shadow: { type: 'outer', color: '000000', blur: 6, offset: 1, angle: 90, opacity: 0.08 }
    },
    source: { file: 'slides/slide-13.js', method: 'addShape' }
  });
  addMappedImage(slide, map, {
    elementId: 'd3.revImg', role: 'image',
    path: '../assets/14-revenue.png',
    x: 0.58, y: 1.43, w: 4.49, h: 2.5,
    source: { file: 'slides/slide-13.js', method: 'addImage' }
  });
  addMappedText(slide, map, {
    elementId: 'd3.revCap', role: 'label',
    text: '经营看板: 4 指标卡 / 7 日趋势 / TOP5 / 上座率 TOP10 / 导出 Excel  |  Revenue dashboard',
    x: 0.55, y: 4.0, w: 4.55, h: 0.3,
    options: {
      fontSize: 7.5, fontFace: FONT_CN, color: TOKENS.ink3, margin: 0
    },
    source: { file: 'slides/slide-13.js', method: 'addText' }
  });

  // Right: bilingual pair (zh vs en) -- the same page at runtime.
  addMappedShape(slide, map, pres.shapes.RECTANGLE, {
    elementId: 'd3.i18nCard', role: 'card',
    x: 5.3, y: 1.4, w: 4.15, h: 2.56,
    options: {
      fill: { color: THEME.light },
      line: { color: TOKENS.rule, width: 0.75 }
    },
    source: { file: 'slides/slide-13.js', method: 'addShape' }
  });
  addMappedText(slide, map, {
    elementId: 'd3.i18nHead', role: 'heading',
    text: '双语运行时切换(同一页面两种语言)', x: 5.46, y: 1.48, w: 3.85, h: 0.22,
    options: {
      fontSize: 9.5, fontFace: FONT_CN, color: THEME.accent,
      bold: true, margin: 0
    },
    source: { file: 'slides/slide-13.js', method: 'addText' }
  });
  addMappedText(slide, map, {
    elementId: 'd3.i18nEn', role: 'body',
    text: 'One page, two languages, switched at runtime',
    x: 5.46, y: 1.69, w: 3.85, h: 0.2,
    options: {
      fontSize: 7.5, fontFace: FONT_EN, color: TOKENS.ink3, margin: 0
    },
    source: { file: 'slides/slide-13.js', method: 'addText' }
  });
  addMappedImage(slide, map, {
    elementId: 'd3.zhImg', role: 'image',
    path: '../assets/01-home-zh.png',
    x: 5.46, y: 1.94, w: 1.86, h: 1.05,
    source: { file: 'slides/slide-13.js', method: 'addImage' }
  });
  addMappedImage(slide, map, {
    elementId: 'd3.enImg', role: 'image',
    path: '../assets/02-home-en.png',
    x: 7.42, y: 1.94, w: 1.86, h: 1.05,
    source: { file: 'slides/slide-13.js', method: 'addText' }
  });
  addMappedShape(slide, map, pres.shapes.RECTANGLE, {
    elementId: 'd3.zhFrame', role: 'decorative',
    x: 5.46, y: 1.94, w: 1.86, h: 1.05,
    options: {
      fill: { type: 'none' },
      line: { color: THEME.accent, width: 1 }
    },
    source: { file: 'slides/slide-13.js', method: 'addShape' }
  });
  addMappedShape(slide, map, pres.shapes.RECTANGLE, {
    elementId: 'd3.enFrame', role: 'decorative',
    x: 7.42, y: 1.94, w: 1.86, h: 1.05,
    options: {
      fill: { type: 'none' },
      line: { color: TOKENS.ruleStrong, width: 1 }
    },
    source: { file: 'slides/slide-13.js', method: 'addShape' }
  });
  addMappedText(slide, map, {
    elementId: 'd3.zhLabel', role: 'label',
    text: 'zh-CN  星辉影城 / 热映影片', x: 5.46, y: 3.02, w: 1.9, h: 0.18,
    options: {
      fontSize: 7, fontFace: FONT_CN, color: THEME.accent,
      bold: true, margin: 0
    },
    source: { file: 'slides/slide-13.js', method: 'addText' }
  });
  addMappedText(slide, map, {
    elementId: 'd3.enLabel', role: 'label',
    text: 'en-US  Star Cinema / Now Showing', x: 7.42, y: 3.02, w: 1.9, h: 0.18,
    options: {
      fontSize: 7, fontFace: FONT_EN, color: THEME.secondary,
      bold: true, margin: 0
    },
    source: { file: 'slides/slide-13.js', method: 'addText' }
  });
  addMappedText(slide, map, {
    elementId: 'd3.i18nNote', role: 'body',
    text: 'vue-i18n 11 legacy:false, 12 组 key 结构对齐, 浏览器语言检测 + localStorage 持久化, fallbackLocale zh-CN, key 覆盖专项测试锁死缺失项.',
    x: 5.46, y: 3.24, w: 3.85, h: 0.4,
    options: {
      fontSize: 7, fontFace: FONT_CN, color: THEME.primary,
      margin: 0, lineSpacingMultiple: 1.05
    },
    source: { file: 'slides/slide-13.js', method: 'addText' }
  });
  addMappedText(slide, map, {
    elementId: 'd3.i18nNoteEn', role: 'body',
    text: 'vue-i18n 11 with structural parity enforced by dedicated tests.',
    x: 5.46, y: 3.66, w: 3.85, h: 0.2,
    options: {
      fontSize: 7, fontFace: FONT_EN, color: TOKENS.ink3,
      italic: true, margin: 0
    },
    source: { file: 'slides/slide-13.js', method: 'addText' }
  });

  // Bottom strip: role guard.
  addMappedShape(slide, map, pres.shapes.RECTANGLE, {
    elementId: 'd3.guard', role: 'card',
    x: 0.55, y: 4.42, w: 8.9, h: 0.58,
    options: {
      fill: { color: THEME.accentWash },
      line: { color: THEME.accent, width: 0.75 }
    },
    source: { file: 'slides/slide-13.js', method: 'addShape' }
  });
  addMappedText(slide, map, {
    elementId: 'd3.guardCn', role: 'body',
    text: '管理端权限双重校验: 前端路由守卫拦截非管理员并跳转首页, 后端每个管理接口独立校验 role=1; 助手侧则用反射测试锁死 @Tool 方法不允许出现 userId 形参, 杜绝 IDOR 越权.',
    x: 0.7, y: 4.5, w: 8.6, h: 0.22,
    options: {
      fontSize: 8, fontFace: FONT_CN, color: THEME.primary, margin: 0
    },
    source: { file: 'slides/slide-13.js', method: 'addText' }
  });
  addMappedText(slide, map, {
    elementId: 'd3.guardEn', role: 'body',
    text: 'Admin routes are guarded on both sides; a reflection test forbids any userId parameter on a @Tool method (IDOR prevention).',
    x: 0.7, y: 4.73, w: 8.6, h: 0.2,
    options: {
      fontSize: 7.5, fontFace: FONT_EN, color: THEME.secondary,
      italic: true, margin: 0
    },
    source: { file: 'slides/slide-13.js', method: 'addText' }
  });

  addPageBadge(slide, map, 'd3', 13);
}

module.exports = { createSlide };
