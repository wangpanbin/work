"""文档一致性闸门 —— 4 项机械检查,随 `python -m unittest discover -s tools` 一起跑。

**为什么要有这个**:本仓的文档里散布着「测试用例数」这类**声明式数字**,而数字天生会漂。
2026-10-25 那一轮就因为后端用例数从 309 变成 312,手工改了 8 个地方(README ×4、
AGENTS.md、发布说明、plans AC-05、实现方案)—— 漏一处就又是一轮「文档说有、代码没有」。
手改不可持续,这类**纯机械**的违规就该由确定性检查兜住。

四项检查:

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

**口径纪律**:
- 只有**当前声明点**参与比对。带历史日期的记录(`docs/稳定性测试报告-2026-10-11.md`、
  `docs/发布说明-v0.2.0.md`、4 份 spec)按「历史记录不改写」豁免 —— 它们说的是当时的事实。
- `docs/实现方案.md` **不再重复用例数**,改为指向 `AGENTS.md`:重复的数字就是缓存,
  缓存必然过期。
"""
from __future__ import annotations

import io
import re
import subprocess
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent

#: 参与「当前数字」比对的文档。历史快照/旧版本发布说明/spec 不在其中。
CURRENT_CLAIM_DOCS = [
    "README.md",
    "AGENTS.md",
    "docs/发布说明-v0.3.0.md",
    "docs/实现方案.md",
    "cinema-web/PRODUCT.md",
    "cinema-web/CLAUDE.md",
    "plans/影院抢票选座系统_战略迭代计划_v1.0.md",
]

#: 不参与比对的历史文档(说明为什么豁免,免得后人以为漏了)
HISTORICAL_DOCS = {
    "docs/稳定性测试报告-2026-10-11.md": "2026-10-11 的历史快照,数字是当时的",
    "docs/发布说明-v0.2.0.md": "v0.2.0 的历史发布说明",
}

MD_LINK = re.compile(r"\[[^\]]*\]\(([^)\s]+)")
BACKTICK = re.compile(r"`([^`\n]+)`")
SKIP_SUBSTRINGS = ("/", " ", "{", "}", "(", ")", "=", "@", "*", "$", ":", "\\", "|")
TOP_DIRS = ("docs/", "sql/", "tools/", "cinema-web/", "cinema-server/", "plans/", "test/",
            "scripts/", ".github/")

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
