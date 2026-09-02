"""Say which tests failed, in the one place a red build can be read.

A GitHub Actions log is not public even when the repository is. `curl` on
the logs endpoint answers 403 without a token, and the job page loads its
lines from a signed URL it will not hand to a stranger. So for anyone who
is not signed in as the owner -- and for any tool acting on their behalf --
a failed run says exactly one thing:

    failure  .github  Process completed with exit code 1.

That is an annotation, and annotations *are* public. This turns the JUnit
XML that Gradle has already written into more of them, so the name of the
test, the assertion it failed and the casualty list it printed are all
readable from the run itself.

Why the printed output and not just the assertion: nearly every measurement
in this project prints a table and asserts on one number out of it. The
number alone says a threshold moved; the table says which language, which
word, and by how much. Truncated rather than dropped, because an annotation
is capped and a report can be tens of kilobytes.

Run from the repository root, after the tests, whatever their result:

    python tools/ci_test_report.py app/build/test-results

Exit status is always 0. This step reports; it does not decide.
"""

import os
import sys
import xml.etree.ElementTree as ET

# Annotations are truncated by GitHub well before this; these keep one
# failure from crowding out the others in a run with several.
MESSAGE_CHARS = 2500
OUTPUT_LINES = 40


def one_line(text):
    """A GitHub annotation is one line: real newlines end it early."""
    return text.replace("\r", "").replace("\n", "%0A")


def tail(text, lines):
    """The end of a printed report, which is where the totals are."""
    kept = [ln for ln in text.replace("\r", "").split("\n") if ln.strip()]
    if len(kept) <= lines:
        return "\n".join(kept)
    return "...\n" + "\n".join(kept[-lines:])


def collect(root):
    """Every failed or errored test case under [root], with its output."""
    out = []
    for dirpath, _, names in os.walk(root):
        for name in sorted(names):
            if not name.endswith(".xml"):
                continue
            path = os.path.join(dirpath, name)
            try:
                suite = ET.parse(path).getroot()
            except ET.ParseError:
                # A run killed mid-write leaves a truncated file. Say so
                # rather than dying here: the other suites still have
                # something to report, and this is the reporting step.
                out.append(("?", os.path.basename(path), "unreadable XML", ""))
                continue
            printed = ""
            for tag in ("system-out", "system-err"):
                node = suite.find(tag)
                if node is not None and node.text:
                    printed += node.text
            for case in suite.iter("testcase"):
                for bad in list(case.findall("failure")) + list(case.findall("error")):
                    detail = (bad.get("message") or "") + "\n" + (bad.text or "")
                    out.append(
                        (case.get("classname", "?"), case.get("name", "?"),
                         detail.strip(), printed)
                    )
    return out


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else "app/build/test-results"
    if not os.path.isdir(root):
        print("no test results under " + root + "; the tests never ran")
        return 0
    failures = collect(root)
    if not failures:
        print("no failing tests in " + root)
        return 0
    print(str(len(failures)) + " failing test(s)")
    for cls, name, detail, printed in failures:
        short = cls.rsplit(".", 1)[-1]
        body = detail[:MESSAGE_CHARS]
        if printed.strip():
            body += "\n\n--- printed by the test ---\n" + tail(printed, OUTPUT_LINES)
        # ::error:: is the channel: it becomes an annotation on the run, and
        # annotations are readable without signing in.
        print("::error title=" + short + "." + name + "::" + one_line(body))
    return 0


if __name__ == "__main__":
    sys.exit(main())
