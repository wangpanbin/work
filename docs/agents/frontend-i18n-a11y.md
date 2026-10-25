# 中英文双语 (i18n)

vue-i18n 11 的配置与 locale key 覆盖闸门。

> **硬约束仍在 `AGENTS.md`** —— 本文件是专题展开, 不覆盖仓库级底线。
> 改动前先读 `AGENTS.md` 的 `Conventions & gotchas` 一节。

## 中英文双语 (i18n)

Design spec: `docs/superpowers/specs/2026-09-22-i18n-zh-en-design.md`. vue-i18n 11 with `legacy: false`, two dictionaries (`zh-CN` / `en-US`), browser-language detection on first visit, localStorage persistence afterwards (user choice beats browser language), `fallbackLocale: 'zh-CN'`.

**`tests/i18n/key-coverage.test.ts` is the safety net for missing keys** — it statically scans `t('...')` usages and diffs both dictionaries, so it catches the case where a key is missing from *both* locales (which `fallbackLocale` cannot rescue; the page would render the raw key). **Run `pnpm test` after touching any locale file or adding a `t()` call.** Also update both `locales/*.ts` together — `locales-shape.test.ts` enforces structural parity.
