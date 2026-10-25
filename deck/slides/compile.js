const path = require('path');
const pptxgen = require('pptxgenjs');
const { createElementMapRecorder } = require('./mavis-ppt-map.cjs');
const { THEME } = require('./theme.js');

const pres = new pptxgen();
pres.layout = 'LAYOUT_16x9';
pres.author = 'Star Cinema Project';
pres.company = 'Course Project';
pres.title = 'Star Cinema - Flash-Sale Ticketing with Real-Time Seat Locking';
pres.subject = 'Bilingual course-project presentation';

const SLIDE_COUNT = 16;
const outDir = path.join(__dirname, 'output');
const baseName = 'star-cinema-seat-booking-bilingual';

const elementMap = createElementMapRecorder({
  fileName: `${baseName}.pptx`,
  filePath: path.join(outDir, `${baseName}.pptx`),
  layout: { name: 'LAYOUT_16x9', widthIn: 10, heightIn: 5.625 },
  expectedSlideCount: SLIDE_COUNT
});
pres.__mavisElementMap = elementMap;

for (let i = 1; i <= SLIDE_COUNT; i += 1) {
  const id = `slide-${String(i).padStart(2, '0')}`;
  // eslint-disable-next-line import/no-dynamic-require, global-require
  const mod = require(`./${id}.js`);
  if (typeof mod.createSlide !== 'function') {
    throw new Error(`${id}.js does not export createSlide(pres, theme)`);
  }
  mod.createSlide(pres, THEME);
}

pres.writeFile({ fileName: path.join(outDir, `${baseName}.pptx`) })
  .then(() => {
    elementMap.assertComplete({ slideCount: SLIDE_COUNT });
    elementMap.writeFile(path.join(outDir, `${baseName}.mavis-ppt-map.json`));
    console.log(`OK wrote ${baseName}.pptx with ${SLIDE_COUNT} slides`);
  })
  .catch((err) => {
    console.error('COMPILE FAILED:', err && err.message ? err.message : err);
    process.exit(1);
  });
