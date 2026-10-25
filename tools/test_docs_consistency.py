"""文档一致性闸门 —— 4 项机械检查,随 `python -m unittest discover -s tools` 一起跑。

**为什么要有这个**:本仓的文档里散布着「测试用例数」这类**声明式数字**,而数字天生会漂。
2026-10-25 那一轮就因为后端用例数从 309 变成 312,手工改了 8 个地方(README ×4、
AGENTS.md、发布说明、plans AC-05、实现方案)—— 漏一处就又是一轮「文档说有、代码没有」。
手改不可持续,这类**纯机械**的违规就该由确定性检查兜住。

七项检查:

1. **引用可解析** —— 文档里指向仓库内文件的路径必须真实存在。
   (原先这个检查住在 `.scratch/check_doc_links.py`,而 `.scratch/` 是 gitignore 的:
   一次清理就没了。收进 `tools/` 才会一直跑。)
2. **后端用例数 = 实测** —— 读 `cinema-server/target/surefire-reports/TEST-*.xml`
   取真值,比对文档里的声明。没跑过后端测试时跳过(不假装通过)。
3. **前端 / Python 数字跨文档一致** —— 这两个没有机器可读的真值来源,
   退而求其次:同一指标在所有「当前声明点」必须说同一个数(改了 README 忘了改
   AGENTS.md 会被这条抓住)。
4. **列表项必须在行首** —— 抓「两行被粘成一行」的编辑事故(本轮踩了 3 次,
   而代码/构建/测试全都发现不了文档里的这种破坏)。
5. **引用必须已入库** —— 文档指向的仓库内文件得在 `git ls-files` 里命中(2026-10-25 新增)。
   「本机有」不等于「仓库里有」:`/test/` 被 ignore 时就是死引用。
6. **文档里点名的裸文件名必须真实存在** —— 2026-10-25 新增(脚本被删了但 4 份文档仍点名它)。
7. **.gitignore 的否定规则不能是死的** —— 2026-10-25 新增(路径写错 = 静默不生效)。
8. **AGENTS.md 必须在 always-loaded 预算内**(32768 字节) —— 2026-10-25 新增。

**口径纪律**:
- 只有**当前声明点**参与比对。带历史日期的记录(`docs/稳定性测试报告-2026-10-11.md`、
  `docs/发布说明-v0.2.0.md`、4 份 spec)按「历史记录不改写」豁免 —— 它们说的是当时的事实。
- `docs/实现方案.md` **不再重复用例数**,改为指向 `AGENTS.md`:重复的数字就是缓存,
  缓存必然过期。
"""
from __future__ import annotations

import io
import re
from fnmatch import fnmatch
import subprocess
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent

#: 参与「当前数字」比对的文档。历史快照/旧版本发布说明/spec 不在其中。
CURRENT_CLAIM_DOCS = [
    "README.md",
    "AGENTS.md",
    "docs/发布说明-v0.4.0.md",
    "docs/实现方案.md",
    "cinema-web/PRODUCT.md",
    "cinema-web/CLAUDE.md",
    "plans/影院抢票选座系统_战略迭代计划_v1.0.md",
]

#: 不参与比对的历史文档(说明为什么豁免,免得后人以为漏了)
HISTORICAL_DOCS = {
    "docs/稳定性测试报告-2026-10-11.md": "2026-10-11 的历史快照,数字是当时的",
    "docs/发布说明-v0.2.0.md": "v0.2.0 的历史发布说明",
    "docs/压测报告.md": "W5 的历史压测报告",
    "docs/压测报告-v2.md": "2026-10 的历史压测报告",
    "docs/发布说明-v0.3.0.md": "v0.3.0 的历史发布说明(它记录的是 v0.3.0 交付时的用例数)",
}

MD_LINK = re.compile(r"\[[^\]]*\]\(([^)\s]+)")

#: 反引号里的**裸文件名**(带这些扩展名)必须真实存在 —— 见 BareFileReferenceTest。
CODE_EXT = (".java", ".ts", ".vue", ".py", ".lua", ".sql", ".md", ".js", ".cjs",
             ".json", ".yml", ".yaml", ".css", ".html", ".xml", ".sh", ".bat", ".ps1",
             ".gradle", ".properties")

#: 通用名/外部物 —— 它们**本来就**不在本仓,别当成断链。
#: 文档里「**故意**提到已删除的文件」的标记词 —— 这一行是历史叙述, 不是断链。
DELETED_MENTION_MARKERS = ("已删除", "被删", "已移除", "已回退", "已废弃", "不再存在", "旧的",
                           "归档", "归档到")

#: 占位写法 —— 文档里用它们表示「某个文件」, 不是具体文件名。
PLACEHOLDER_PATTERNS = ("XX", "xxx", "yyy", "zzz", "TODO", "TBD")

BARE_NAME_WHITELIST = {
    "package.json", "package-lock.json", "pnpm-lock.yaml", "yarn.lock",
    "pom.xml", "build.gradle", "gradlew", "dockerfile", "makefile",
    "application.properties", "logback.xml", "index.html", "index.js",
    "readme.md", "license", "changelog.md", "contributing.md",
}
BACKTICK = re.compile(r"`([^`\n]+)`")
#: 片段里出现这些字符就**不是**路径。
#: ⚠️ 这里**不能**把 "/" 列进来 —— 带了目录分隔符的恰恰是最像路径的那类,
#:    把它们全排除掉等于把检查器的主要战场扔了(2026-10-25 二次评审发现)。
SKIP_SUBSTRINGS = (" ", "{", "}", "(", ")", "=", "@", "*", "$", ":", "\\", "|", "?", "<", ">",
                  "...")   # "..." 是文档里的省略写法(test/.../X.java), 不是字面路径
TOP_DIRS = ("docs/", "sql/", "tools/", "cinema-web/", "cinema-server/", "plans/", "test/",
            "scripts/", ".github/", ".scratch/", "deck/")

# ---- 数字声明的模式(每条都贴着真实文案写,不做宽泛匹配) ----
BACKEND_TEST_PATTERNS = [
    re.compile(r"(\d+)\s*个\s*JUnit5"),          # "312 个 JUnit5 + Mockito 用例"
    re.compile(r"Tests run:\s*(\d+)"),             # "# Tests run: 312, Failures: 0"
    re.compile(r"后端\s*(\d+)\s*用例"),              # "后端 312 用例"
]
BACKEND_CLASS_PATTERNS = [
    re.compile(r"(\d+)\s*个测试类"),                # "47 个测试类"
]
BACKEND_PAIR_PATTERN = re.compile(r"\*\*(\d+)\s*/\s*(\d+)\s*类\*\*")  # "**312 / 47 类**"

FRONTEND_PATTERNS = [
    (re.compile(r"(\d+)\s*用例跨\s*(\d+)\s*个"), "tests", "files"),   # AGENTS.md
    (re.compile(r"Test Files:\s*(\d+)\s*passed,\s*Tests:\s*(\d+)\s*passed"), "files", "tests"),
    (re.compile(r"(\d+)\s*个\s*\.test\.ts\s*/\s*(\d+)\s*条"), "files", "tests"),
    (re.compile(r"(\d+)\s*个\s*\.test\.ts\s*文件"), "files", "files"),
    (re.compile(r"(\d+)\s*个\s*Vitest\s*单元测试"), "tests", "tests"),
    (re.compile(r"\*\*(\d+)\s*/\s*(\d+)\s*文件\*\*"), "tests", "files"),
]

PYTHON_PATTERNS = [
    re.compile(r"Ran\s*(\d+)\s*tests"),                        # "# Ran 67 tests ... OK"
    re.compile(r"\*\*(\d+)\s*个用例\*\*"),                     # "**67 个用例**"
    re.compile(r"Python 工具\(`unittest`\)\s*\|\s*—\s*\|\s*\*\*(\d+)\*\*"),
]

#: 「被粘住」的列表标记: 紧贴前一字符(没有空格), 且后面是强调/代码。
#: 正文里的「家族 + `--rule`」不算 —— 那里 marker 前面有空格。
GLUE = re.compile(r"(?<=[\u4e00-\u9fff。，：、；])(?:-|\*|\+|\d+\.)\s")
#: 粘住之后必须紧跟**强调**或**代码**
GLUE_AFTER = re.compile(r"^\s*(\*\*|`)")


#: 文档里**明确说明「不存在 / 从未创建」**的路径 —— 它们不是引用, 是叙述。
KNOWN_ABSENT_PATHS = {
    "docs/api.md": "实现方案 §3 明说: 早期版本列过, 从未创建, 已移除",
    "docs/架构设计.md": "同上",
}

#: 历史文档 —— 里面指向的是**当时**的文件名(有的后来被重构掉了)。
#: 那不是断链, 是历史; 按仓库口径「历史记录不改写」豁免。
REFERENCE_EXEMPT_DOCS = {
    "docs/压测报告-v2.md": "2026-10 的历史压测报告, 引用的是当时的文件名",
    "docs/superpowers/specs/2026-08-31-p0-increment-design.md": "历史 spec",
    "test/playwright-cli/RESULTS.md": "2026-08 的人工 CLI 验收记录",
}


def is_ignored(path: Path) -> bool:
    """这个文件是不是被 .gitignore 挡住了(git check-ignore 的批量版)。"""
    rel = path.relative_to(REPO).as_posix()
    out = subprocess.run(["git", "-c", "core.quotepath=false", "check-ignore", "-q", "--", rel],
                         cwd=str(REPO), capture_output=True, text=True)
    return out.returncode == 0


#: 历史文档 —— 里面指向的是**当时**的文件名(有的后来被重构掉了)。
#: 那不是断链, 是历史; 仓库既有口径就是「历史记录不改写」。
#: 与数字比对的 HISTORICAL_DOCS 同一把尺子。
HISTORICAL_REF_PREFIXES = ("docs/superpowers/specs/", "docs/archive/")


def is_historical_ref(rel: str) -> bool:
    if rel in HISTORICAL_DOCS:
        return True
    return rel.startswith(HISTORICAL_REF_PREFIXES)


def repo_markdown_files() -> list:
    """仓库里所有 markdown(已跟踪的 + 磁盘上存在的未跟踪文档)。"""
    out = subprocess.run(["git", "ls-files", "*.md"], cwd=str(REPO),
                         capture_output=True, text=True, encoding="utf-8").stdout.split("\n")
    files = [f.strip() for f in out if f.strip()]
    for p in (REPO / "docs").rglob("*.md"):
        rel = p.relative_to(REPO).as_posix()
        if rel not in files:
            files.append(rel)
    return files


def read(rel: str) -> str:
    with io.open(REPO / rel, encoding="utf-8") as fh:
        return fh.read()


def line_of(text: str, pos: int) -> int:
    return text[:pos].count("\n") + 1


def is_path_like(text: str) -> bool:
    """只认以仓库一级目录开头的反引号片段 —— 裸文件名指的是代码符号, 查它们全是噪声。"""
    if any(c in text for c in SKIP_SUBSTRINGS):
        return False
    text = text.replace("\\", "/")
    if not text.startswith(TOP_DIRS):
        return False
    return bool(re.search(r"\.[A-Za-z0-9]+$", text))


class DocReferenceTest(unittest.TestCase):
    """① 文档里指向仓库内文件的引用必须真实存在。"""

    def test_no_dangling_references(self):
        broken = []
        checked = 0
        for rel in repo_markdown_files():
            path = REPO / rel
            if not path.exists():
                continue
            if rel in REFERENCE_EXEMPT_DOCS:
                continue
            text = read(rel)
            base = path.parent
            seen = set()
            for m in MD_LINK.finditer(text):
                target = m.group(1).strip()
                if target.startswith(("http://", "https://", "mailto:", "#")):
                    continue
                target = target.split("#")[0].split("?")[0]
                if not target:
                    continue
                if target in KNOWN_ABSENT_PATHS:
                    continue
                cand = (base / target).resolve()
                checked += 1
                if cand in seen:
                    continue
                seen.add(cand)
                if not cand.exists():
                    broken.append(f"{rel}:{line_of(text, m.start())}  {target}")
            for m in BACKTICK.finditer(text):
                target = m.group(1).strip()
                if not is_path_like(target):
                    continue
                if target in KNOWN_ABSENT_PATHS:
                    continue
                cand = (REPO / target).resolve()
                checked += 1
                if cand in seen:
                    continue
                seen.add(cand)
                if not cand.exists():
                    broken.append(f"{rel}:{line_of(text, m.start())}  {target}")
        self.assertEqual([], broken,
                         "文档里有指向不存在文件的引用:\n  " + "\n  ".join(broken))
        self.assertGreater(checked, 0, "一条引用都没扫到 —— 检查器本身失效了")


class BackendCountTest(unittest.TestCase):
    """② 后端用例数 / 测试类数必须等于 surefire 实测。"""

    @classmethod
    def setUpClass(cls):
        cls.reports = sorted((REPO / "cinema-server" / "target" / "surefire-reports").glob("TEST-*.xml"))
        if not cls.reports:
            cls.tests = cls.classes = None
            return
        import xml.etree.ElementTree as ET
        cls.tests = 0
        for p in cls.reports:
            root = ET.parse(p).getroot()
            cls.tests += int(root.get("tests", 0))
        cls.classes = len(cls.reports)

    def _claims(self):
        out = []
        for rel in CURRENT_CLAIM_DOCS:
            if not (REPO / rel).exists():
                continue
            text = read(rel)
            for pat in BACKEND_TEST_PATTERNS:
                for m in pat.finditer(text):
                    out.append((rel, line_of(text, m.start()), int(m.group(1))))
            for pat in BACKEND_CLASS_PATTERNS:
                for m in pat.finditer(text):
                    out.append((rel, line_of(text, m.start()), int(m.group(1))))
            for m in BACKEND_PAIR_PATTERN.finditer(text):
                out.append((rel, line_of(text, m.start()), int(m.group(1))))
                out.append((rel, line_of(text, m.start()), int(m.group(2))))
        return out

    def test_backend_counts_match_surefire(self):
        if self.tests is None:
            self.skipTest("没有 surefire 报告(先跑一次 mvn test 才能比对)")
        claims = self._claims()
        self.assertTrue(claims, "一条后端数字声明都没扫到 —— 检查器本身失效了")
        bad = []
        for rel, line, value in claims:
            # 同一个数字既可能是用例数也可能是类数, 两边都要对得上
            if value not in (self.tests, self.classes):
                bad.append(f"{rel}:{line}  写了 {value}, 实测 用例={self.tests} 类={self.classes}")
        self.assertEqual([], bad, "文档里的后端测试数字与实测不一致:\n  " + "\n  ".join(bad))


class CrossDocCountTest(unittest.TestCase):
    """③ 前端 / Python 数字在所有当前声明点之间必须一致。"""

    def _claims(self, patterns):
        """返回 (rel, line, label, value) 列表 —— label 是 'tests' / 'files'。

        模式上的两个标签决定第 1、第 2 个捕获组各自是什么指标
        (比如「Test Files: 35 passed, Tests: 547 passed」的第 1 组是文件数不是用例数)。
        """
        out = []
        for rel in CURRENT_CLAIM_DOCS:
            if not (REPO / rel).exists():
                continue
            text = read(rel)
            for pat, lab1, lab2 in patterns:
                for m in pat.finditer(text):
                    out.append((rel, line_of(text, m.start()), lab1, int(m.group(1))))
                    if lab2 != lab1:
                        out.append((rel, line_of(text, m.start()), lab2, int(m.group(2))))
        return out

    def test_frontend_counts_agree(self):
        claims = self._claims(FRONTEND_PATTERNS)
        self.assertTrue(claims, "一条前端数字声明都没扫到 —— 检查器本身失效了")
        tests = {c[3] for c in claims if c[2] == "tests"}
        files = {c[3] for c in claims if c[2] == "files"}
        self.assertEqual(1, len(tests), "前端用例数在文档之间不一致: " + str(sorted(tests)))
        self.assertEqual(1, len(files), "前端测试文件数在文档之间不一致: " + str(sorted(files)))

    def test_python_counts_agree(self):
        claims = self._claims([(p, "tests", "tests") for p in PYTHON_PATTERNS])
        self.assertTrue(claims, "一条 Python 数字声明都没扫到 —— 检查器本身失效了")
        values = {c[3] for c in claims}
        self.assertEqual(1, len(values), "Python 用例数在文档之间不一致: " + str(sorted(values)))

    def test_python_count_matches_reality(self):
        """文档里声明的 Python 用例数 = 实际 `def test_` 数。

        <p>只做「跨文档一致」是不够的: 各文档彼此相等, 但可以一起离 reality 越来越远。
        2026-10-25 实测: 加了 3 个检查类后实际 84, 文档还写着 81, 而闸门是绿的。
        """
        actual = 0
        for f in sorted((REPO / "tools").glob("test_*.py")):
            actual += len(re.findall(r"(?m)^\s*def test_", f.read_text(encoding="utf-8")))
        self.assertGreater(actual, 0, "没数到任何 Python 用例 —— 检查器本身失效了")
        claims = self._claims([(p, "tests", "tests") for p in PYTHON_PATTERNS])
        bad = [f"{rel}:{line}  写了 {value}, 实测 {actual}"
               for rel, line, _lab, value in claims if value != actual]
        self.assertEqual([], bad,
                         "文档里的 Python 用例数与实测不一致(实测 def test_ 数):\n  "
                         + "\n  ".join(bad))


#: 明确「就是要不入库」的路径 —— 逐条写清理由, 不做通配豁免。
#: 不在白名单里的「磁盘上有、但没入库」= 证据没进仓库 = 新克隆拿不到。
UNTRACKED_ALLOWED = {
    # 本机配置(含数据库口令), 必须 gitignore —— 它是**故意**不入库的
    "cinema-server/src/main/resources/application-dev.yml",
}


def git_tracked_files() -> set:
    """git ls-files 的结果(仓库相对路径, posix 分隔)。

    ⚠️ 必须加 ``-c core.quotepath=false``: 否则 git 把中文路径转义成八进制
    (``docs/\\345\\217\\213...``), 中文文档名一个都命中不了, 检查器会误报一片。
    """
    out = subprocess.run(["git", "-c", "core.quotepath=false", "ls-files"], cwd=str(REPO),
                         capture_output=True, text=True, encoding="utf-8").stdout
    return {line.strip() for line in out.split("\n") if line.strip()}


class TrackedReferenceTest(unittest.TestCase):
    """⑤ 文档引用的仓库内文件必须**已入库** —— 磁盘上存在不等于仓库里有。

    <p>第 ① 项查的是「文件在不在磁盘上」; 这一项查的是「它在不在仓库里」。
    两者中间隔着 .gitignore —— 指向被忽略的文件的文档引用, 对**新克隆仓库的读者**
    就是死链(E2E 报告与验收截图那两处就是活例子)。
    """

    def test_referenced_paths_are_tracked(self):
        tracked = git_tracked_files()
        self.assertTrue(tracked, "git ls-files 没返回任何文件 —— 检查器失效了")
        offenders = []
        checked = 0
        for rel in repo_markdown_files():
            p = REPO / rel
            if not p.exists():
                continue
            if rel in REFERENCE_EXEMPT_DOCS:
                continue
            text = read(rel)
            base = p.parent
            for m in BACKTICK.finditer(text):
                target = m.group(1).strip()
                if not is_path_like(target):
                    continue
                checked += 1
                if target in tracked or target in UNTRACKED_ALLOWED:
                    continue
                # 磁盘上都没有的, 归第 ① 项管(悬空引用), 这里不重复报
                if not (REPO / target).exists():
                    continue
                offenders.append(f"{rel}:{line_of(text, m.start())}  {target}")
        self.assertEqual([], offenders,
                         "文档引用了**没入库**的文件 —— 本机有, 新克隆的仓库里没有:\n  "
                         + "\n  ".join(offenders))
        self.assertGreater(checked, 0, "一条带目录的引用都没扫到 —— 检查器本身失效了")


class BareFileReferenceTest(unittest.TestCase):
    """⑥ 文档里点名的**裸文件名**必须真实存在。

    <p>**为什么要有这一项**:第 ① 项只查「以仓库一级目录开头」的路径。
    裸文件名(`replace_seat_bitmap.lua` / `SeatLuaService.java`)一律被 `is_path_like`
    判 False → 从来没被检查过。2026-10-25 的一次评审里,位图重建脚本被删了,
    而 4 份文档仍在正文里点名它 —— 所有自动闸门静默放过,只靠评审代理抓出来。

    <p>判据是「**仓库任何地方**有同名文件」,不是「已入库」:
    `application-dev.yml` 这类**故意** gitignore 的本机配置照样存在,不该被误伤;
    而**已删除**的脚本哪儿都不存在 → 判红。
    """

    def test_bare_filenames_exist(self):
        # 仓库里所有文件名的 basename 集合
        basenames = set()
        for p in REPO.rglob("*"):
            if p.is_file() and not any(
                part in (".git", "node_modules", "target", "dist", ".idea", ".vscode")
                for part in p.parts
            ):
                basenames.add(p.name.lower())
        self.assertTrue(basenames, "没扫到任何文件 —— 检查器本身失效了")

        offenders = []
        checked = 0
        for rel in repo_markdown_files():
            path = REPO / rel
            if not path.exists():
                continue
            if is_historical_ref(rel):
                continue                       # 历史文档: 指向当时的文件名不是断链
            text = read(rel)
            for m in BACKTICK.finditer(text):
                token = m.group(1).strip()
                if "/" in token or "\\" in token:
                    continue                       # 带目录的归第 ① 项管
                if "*" in token or "?" in token:
                    continue                       # 通配符, 不是文件名
                if not token.lower().endswith(CODE_EXT):
                    continue                       # 不是文件名
                if token.lower().startswith("."):
                    continue                       # 点开头的形态标记(.ts / .test.ts), 不是文件名
                if token.lower() in BARE_NAME_WHITELIST:
                    continue
                line_no = line_of(text, m.start())
                lines_all = text.split("\n")
                # 标记词可能落在**下一行**(长句会换行)—— 窗口取「本行 + 下一行」
                window = lines_all[line_no - 1] + "\n" + (lines_all[line_no] if line_no < len(lines_all) else "")
                if any(k in window for k in DELETED_MENTION_MARKERS):
                    continue                       # 历史叙述: 故意点名已删的文件
                if any(ph in token for ph in PLACEHOLDER_PATTERNS):
                    continue                       # 占位写法(slide-XX.js), 不是文件名
                checked += 1
                if token.lower() not in basenames:
                    offenders.append(f"{rel}:{line_no}  {token}")
        self.assertEqual([], offenders,
                         "文档里点名的文件**不存在**(多半是被删了但文档没同步):\n  "
                         + "\n  ".join(offenders))
        self.assertGreater(checked, 0, "一条裸文件名都没扫到 —— 检查器本身失效了")


class GitignoreNegationTest(unittest.TestCase):
    """⑦ .gitignore 的否定规则(`!…`)不能是死规则。

    <p>**为什么要有这一项**:否定规则**路径写错**时**静默不生效** —— 不报错、
    不警告,只是那个文件继续被忽略。2026-10-25 实测踩到:规则写成 `!deck/compile.js`,
    而源码实际在 `deck/slides/` 下 → 文档声称「生成器源码入库」,实际根本没入库。
    只有 `git ls-files` 能揭穿。

    <p>判据: 每条 `!` 规则至少要匹配到 1 个**真的没被忽略**的文件。
    """

    def test_negation_rules_are_live(self):
        gi = REPO / ".gitignore"
        if not gi.exists():
            self.skipTest("没有 .gitignore")
        patterns = []
        for line in gi.read_text(encoding="utf-8").split("\n"):
            line = line.strip()
            if line.startswith("!") and len(line) > 1:
                patterns.append(line[1:])

        offenders = []
        for pat in patterns:
            # ⚠️ 父目录要取「规则所在的那个目录」: 文件型规则(!deck/x.js)的父目录是 deck/,
            #    不是这个文件本身 —— 否则文件当然「不存在」→ 被当成模板遗留跳过 → 抓不到路径写错。
            pat_path = Path(pat)
            parent = pat_path.parent
            base = REPO / parent if str(parent) != "." else REPO
            if not base.exists():
                # 父目录都不存在 = 模板遗留(如 .mvn/ 这类从未建过的目录),
                # 与「目录存在但文件不在」(路径写错)要分开 —— 后者才判红。
                continue
            matched = []
            if pat_path.is_dir():
                matched = [f for f in base.rglob("*") if f.is_file()]
            else:
                matched = [f for f in base.rglob(pat_path.name) if f.is_file()]
            if not matched:
                offenders.append(f"{pat}  —— 没匹配到任何文件(规则是死的)")
                continue
            alive = [m for m in matched if not is_ignored(m)]
            if not alive:
                offenders.append(f"{pat}  —— 匹配到的文件**仍被忽略**(否定规则没生效)")
        self.assertEqual([], offenders,
                         ".gitignore 里有失效的否定规则(路径写错就会静默不生效):\n  "
                         + "\n  ".join(offenders))


class AgentsMdBudgetTest(unittest.TestCase):
    """⑧ AGENTS.md 必须在 always-loaded 预算内。

    <p>AGENTS.md 是**每轮都进上下文**的文件(运行时预算 32768 字节, UTF-8)。
    超预算 = 尾部被截 = **最新的规则与专题文档指针表根本没进上下文** ——
    而那正是「改了机制,描述机制的那一串文字最容易漏」的放大器。

    <p>2026-10-25 实测:v0.3.0 那一轮刻意把它压到 27,370 字节(预算下留 16% 余量),
    本轮加规则时涨到 35,047 → **超 2,279 字节** —— 也就是说这一轮后半程我写的规则
    有一部分根本没进过上下文。已砍回 31,443,并用这道闸门守住。
    """

    #: 运行时给项目指令留的字节预算(与 local-runtime-v2 的 32768 一致)
    BUDGET = 32768

    def test_agents_md_within_always_loaded_budget(self):
        p = REPO / "AGENTS.md"
        if not p.exists():
            self.skipTest("没有 AGENTS.md")
        size = len(p.read_bytes())
        self.assertLessEqual(size, self.BUDGET,
                             f"AGENTS.md {size} 字节,超预算 {size - self.BUDGET} 字节 —— "
                             "尾部会被截断(最新的规则与指针表没进上下文)。"
                             "把细节下放到 docs/agents/ 的专题文档,别在 always-loaded 文件里堆。")


class ListMarkerTest(unittest.TestCase):
    """④ 列表项必须在行首 —— 抓「两行被粘成一行」的编辑事故。"""

    def test_list_markers_start_their_line(self):
        offenders = []
        for rel in repo_markdown_files():
            path = REPO / rel
            if not path.exists():
                continue
            for i, line in enumerate(read(rel).split("\n"), start=1):
                for m in GLUE.finditer(line):
                    rest = line[m.end():]
                    if not GLUE_AFTER.match(rest):
                        continue  # 不是「被粘住」的形态(正文里的连字符不算)
                    offenders.append(f"{rel}:{i}  {line.strip()[:90]}")
                    break
        self.assertEqual([], offenders,
                         "有列表项被粘在上一行末尾(编辑事故, 文档类破坏不会被任何构建抓到):\n  "
                         + "\n  ".join(offenders))


if __name__ == "__main__":
    unittest.main()
