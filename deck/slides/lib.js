// Shared slide layout helpers.
// Bilingual pattern: Chinese line on top, muted English line directly beneath.
const {
  THEME, TOKENS, FONT_CN, FONT_EN, PAGE_W, PAGE_H, softShadow
} = require('./theme.js');
const { addMappedText, addMappedImage, addMappedShape } = require('./mavis-ppt-map.cjs');

// Standard content-slide header: small EN kicker + big CN title + EN subtitle.
function addHeader(slide, map, opts) {
  const { id, kickerEn, titleCn, titleEn } = opts;
  if (kickerEn) {
    addMappedText(slide, map, {
      elementId: `${id}.kicker`,
      role: 'label',
      text: kickerEn,
      x: 0.55, y: 0.34, w: 8.9, h: 0.24,
      options: {
        fontSize: 10, fontFace: FONT_EN, color: THEME.accent,
        charSpacing: 2, bold: true, margin: 0
      },
      source: { file: 'slides/lib.js', method: 'addText' }
    });
  }
  addMappedText(slide, map, {
    elementId: `${id}.title`,
    role: 'title',
    text: titleCn,
    x: 0.55, y: 0.58, w: 8.9, h: 0.46,
    options: {
      fontSize: 25, fontFace: FONT_CN, color: THEME.primary,
      bold: true, margin: 0, fit: 'shrink'
    },
    source: { file: 'slides/lib.js', method: 'addText' }
  });
  if (titleEn) {
    addMappedText(slide, map, {
      elementId: `${id}.titleEn`,
      role: 'subtitle',
      text: titleEn,
      x: 0.55, y: 1.04, w: 8.9, h: 0.24,
      options: {
        fontSize: 11, fontFace: FONT_EN, color: THEME.secondary,
        margin: 0
      },
      source: { file: 'slides/lib.js', method: 'addText' }
    });
  }
}

// A vertical accent bar to the left of a title block (no underline rule).
function addTitleTick(slide, map, pres, id) {
  addMappedShape(slide, map, pres.shapes.RECTANGLE, {
    elementId: `${id}.tick`,
    role: 'decorative',
    x: 0.36, y: 0.36, w: 0.055, h: 0.9,
    options: { fill: { color: THEME.accent }, line: { color: THEME.accent, width: 0.5 } },
    source: { file: 'slides/lib.js', method: 'addShape' }
  });
}

// Mandatory page-number badge for every non-cover slide.
function addPageBadge(slide, map, id, n) {
  addMappedText(slide, map, {
    elementId: `${id}.pageNo`,
    role: 'pageNumber',
    text: String(n).padStart(2, '0'),
    x: 9.3, y: 5.1, w: 0.45, h: 0.28,
    options: {
      fontSize: 10, fontFace: FONT_EN, color: THEME.ink3 || TOKENS.ink3,
      align: 'right', margin: 0
    },
    source: { file: 'slides/lib.js', method: 'addText' }
  });
}

// One bilingual bullet row: CN bold line + EN muted line beneath.
function addBiLine(slide, map, opts) {
  const { id, cn, en, x, y, w, size = 12, enSize = 9.5 } = opts;
  addMappedText(slide, map, {
    elementId: `${id}.cn`,
    role: 'body',
    text: cn,
    x, y, w, h: 0.24,
    options: {
      fontSize: size, fontFace: FONT_CN, color: THEME.primary,
      bold: true, margin: 0
    },
    source: { file: 'slides/lib.js', method: 'addText' }
  });
  addMappedText(slide, map, {
    elementId: `${id}.en`,
    role: 'body',
    text: en,
    x, y: y + 0.235, w, h: 0.22,
    options: {
      fontSize: enSize, fontFace: FONT_EN, color: THEME.secondary,
      margin: 0
    },
    source: { file: 'slides/lib.js', method: 'addText' }
  });
}

// Screenshot with a hairline frame + bilingual caption underneath.
function addShot(slide, map, pres, opts) {
  const { id, path, x, y, w, h, capCn, capEn, shadow = true } = opts;
  if (shadow) {
    addMappedShape(slide, map, pres.shapes.RECTANGLE, {
      elementId: `${id}.frame`,
      role: 'decorative',
      x, y, w, h,
      options: {
        fill: { color: 'FFFFFF' },
        line: { color: TOKENS.rule, width: 0.75 },
        shadow: softShadow()
      },
      source: { file: 'slides/lib.js', method: 'addShape' }
    });
  }
  addMappedImage(slide, map, {
    elementId: `${id}.img`,
    role: 'image',
    path,
    x: x + 0.03, y: y + 0.03, w: w - 0.06, h: h - 0.06,
    source: { file: 'slides/lib.js', method: 'addImage' }
  });
  if (capCn) {
    addMappedText(slide, map, {
      elementId: `${id}.capCn`,
      role: 'label',
      text: capCn,
      x, y: y + h + 0.05, w, h: 0.2,
      options: {
        fontSize: 9, fontFace: FONT_CN, color: THEME.primary, margin: 0
      },
      source: { file: 'slides/lib.js', method: 'addText' }
    });
  }
  if (capEn) {
    addMappedText(slide, map, {
      elementId: `${id}.capEn`,
      role: 'label',
      text: capEn,
      x, y: y + h + 0.24, w, h: 0.2,
      options: {
        fontSize: 8, fontFace: FONT_EN, color: THEME.secondary, margin: 0
      },
      source: { file: 'slides/lib.js', method: 'addText' }
    });
  }
}

// A numbered stat card used on metric slides.
function addStatCard(slide, map, pres, opts) {
  const { id, x, y, w, h, value, unit, labelCn, labelEn, tone } = opts;
  const accent = tone || THEME.accent;
  addMappedShape(slide, map, pres.shapes.RECTANGLE, {
    elementId: `${id}.card`,
    role: 'card',
    x, y, w, h,
    options: {
      fill: { color: THEME.light },
      line: { color: TOKENS.rule, width: 0.75 },
      shadow: softShadow()
    },
    source: { file: 'slides/lib.js', method: 'addShape' }
  });
  addMappedShape(slide, map, pres.shapes.RECTANGLE, {
    elementId: `${id}.bar`,
    role: 'decorative',
    x, y, w: 0.045, h,
    options: { fill: { color: accent }, line: { color: accent, width: 0.5 } },
    source: { file: 'slides/lib.js', method: 'addShape' }
  });
  addMappedText(slide, map, {
    elementId: `${id}.value`,
    role: 'value',
    text: unit ? `${value} ${unit}` : String(value),
    x: x + 0.16, y: y + 0.1, w: w - 0.26, h: 0.42,
    options: {
      fontSize: 21, fontFace: FONT_EN, color: accent, bold: true,
      margin: 0, fit: 'shrink'
    },
    source: { file: 'slides/lib.js', method: 'addText' }
  });
  addMappedText(slide, map, {
    elementId: `${id}.labelCn`,
    role: 'label',
    text: labelCn,
    x: x + 0.16, y: y + 0.54, w: w - 0.26, h: 0.2,
    options: {
      fontSize: 9.5, fontFace: FONT_CN, color: THEME.primary, margin: 0
    },
    source: { file: 'slides/lib.js', method: 'addText' }
  });
  addMappedText(slide, map, {
    elementId: `${id}.labelEn`,
    role: 'label',
    text: labelEn,
    x: x + 0.16, y: y + 0.73, w: w - 0.26, h: 0.2,
    options: {
      fontSize: 8, fontFace: FONT_EN, color: THEME.secondary, margin: 0
    },
    source: { file: 'slides/lib.js', method: 'addText' }
  });
}

module.exports = {
  addHeader, addTitleTick, addPageBadge, addBiLine, addShot, addStatCard,
  THEME, TOKENS, FONT_CN, FONT_EN, PAGE_W, PAGE_H, softShadow
};
