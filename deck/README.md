# 答辩物料生成器(v0.4.0 起随仓库入库)

这是「影院抢票选座系统」的双语课程设计汇报幻灯片的**生成器源码**。
仓库里只留源码(约 80KB);**产物**(PPTX / PDF / 16 张成片 PNG)不入库 ——
它们可由这里重新生成, 入库只会永久撑大仓库。

> 2026-10-25 之前, 这个目录整个被 gitignore, 于是「怎么重新生成」只活在某个人脑子里。
> v0.4.0 把源码收进仓库, 并补了这份说明 —— 产物丢了能重建, 源码丢了就真没了。

## 目录结构

```
deck/
  README.md              ← 本文件
  assets/                ← 应用截图(16 张, 不入库)—— 幻灯片要引用它们
    01-home-zh.png … 16-seat-conflict.png
  slides/                ← 生成器源码(入库)
    compile.js           ← 入口: 按顺序装配 16 页
    lib.js               ← 页面构件
    theme.js             ← 配色与排版常量
    mavis-ppt-map.cjs    ← 元素映射记录器(校验 16 页都生成了)
    slide-01.js … slide-16.js   ← 每页一个模块, 导出 createSlide(pres, theme)
    output/              ← 产物目录(不入库)
```

## 怎么重新生成 PPTX

**前置**:Node.js(本机 v24)+ `pptxgenjs`。

```powershell
# ① 依赖(pptxgenjs 全局装过就能跳过这步)
npm i -g pptxgenjs

# ② 产物目录必须先存在 —— pptxgenjs 不会自己建
mkdir deck\slides\output -Force

# ③ 跑生成器
cd deck\slides
node compile.js
```

成功时输出:

```
OK wrote star-cinema-seat-booking-bilingual.pptx with 16 slides
```

产物落在 `deck/slides/output/`:
- `star-cinema-seat-booking-bilingual.pptx` —— 16 页 16:9 幻灯片
- `star-cinema-seat-booking-bilingual.mavis-ppt-map.json` —— 元素映射(校验用)

## 三个会踩的坑(实测)

1. **`output/` 目录不会自动创建** —— 不建就直接报
   `ENOENT: no such file or directory, open '.../output/....pptx'`。
2. **`assets/` 必须和 `slides/` 平级** —— 幻灯片里用 `../assets/06-seat-select.png`
   这种相对路径引用截图, 目录层级错了就报 `Unable to read media`。
3. **每页必须导出 `createSlide(pres, theme)`** —— `compile.js` 找不到就直接抛错;
   `mavis-ppt-map.cjs` 还会在收尾时断言「16 页都齐了」, 少一页就报错。

## PDF 与成片 PNG 怎么来

`compile.js` **只产出 PPTX**。
`star-cinema-seat-booking-bilingual.pdf` 与 `output/` 下的 16 张成片 PNG
是另一条流水线的产物(PPT 渲染 → 图片 → PDF), 那套工具链**不在本仓库里** ——
本机有 Microsoft Office 2016, 可以用 COM 自动化做转换(见 `office-com` 技能)。

`assets/` 里的 16 张应用截图是**运行中的前端**截的, 与本仓库的浏览器回归套件
(`test/e2e/`)是两条不同的线 —— 要重截, 起前后端后按 `deck/assets/` 的编号顺序补。

## 改幻灯片

- 改文案/布局 → 编辑对应的 `slide-XX.js`(每页一个模块, 互不影响)。
- 改配色/字号 → 编辑 `theme.js` 的 `THEME` 常量, 全部 16 页一起变。
- 改页数 → `compile.js` 里的 `SLIDE_COUNT` 与 `mavis-ppt-map.cjs` 的
  `expectedSlideCount` **要同时改**, 否则元素映射的断言会红。
