#!/usr/bin/env python3
"""Static preflight for `ci/mutation-probes.sh` -- no Gradle, no device, ~50 ms.

WHY THIS EXISTS. `mutation-probes.sh` applies each probe by string replacement and
aborts with `PROBE TEXT NOT FOUND` when the text it is looking for is no longer in
the file exactly once. That abort happens *during* a run, after however many probes
already passed, and a run of the full table takes far longer than the harness tool
timeout -- so the cheapest way to learn that a merge moved a line out from under a
sibling lane's probe is to find out before starting.

It is deliberately a **separate file** rather than a mode of `mutation-probes.sh`:
while a fleet is live, every lane is editing that script, and a new file has no
conflict to resolve.

WHAT PASSING MEANS: every probe's `file` constant resolves to a path that exists,
and its search text occurs in that file **exactly once**. That is precisely the
precondition `apply()` needs and nothing more. It says nothing about whether the
mutation still fails the test it names -- only a real run says that.

    ./ci/probe-preflight.py            # from the repository root

Exit 0 when clean, 1 with one line per problem otherwise.
"""

import ast
import os
import sys

SCRIPT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "mutation-probes.sh")


def python_body(path):
    """The probe script is a shell wrapper around one heredoc'd Python program."""
    lines = open(path).read().split("\n")
    start = next(i for i, l in enumerate(lines) if l.startswith("exec python3"))
    end = next(i for i, l in enumerate(lines) if l.strip() == "PY" and i > start)
    return "\n".join(lines[start + 1 : end])


def main():
    tree = ast.parse(python_body(SCRIPT))

    # Module-level `NAME = "some/path.kt"` bindings: the probe table names files this way.
    paths = {}
    probes = None
    for node in tree.body:
        if not isinstance(node, ast.Assign):
            continue
        names = [t.id for t in node.targets if isinstance(t, ast.Name)]
        if "PROBES" in names:
            probes = node.value
        elif isinstance(node.value, ast.Constant) and isinstance(node.value.value, str):
            for n in names:
                paths[n] = node.value.value

    if probes is None:
        sys.exit("could not find PROBES in " + SCRIPT)

    problems = []
    contents = {}
    checked = 0

    for element in probes.elts:
        if not isinstance(element, ast.Tuple):
            continue
        name = ast.literal_eval(element.elts[0])

        target = element.elts[1]
        if isinstance(target, ast.Name):
            path = paths.get(target.id)
        elif isinstance(target, ast.Constant):
            path = target.value
        else:
            path = None
        if path is None:
            problems.append(f"{name}: cannot resolve which file it mutates")
            continue

        try:
            needle = ast.literal_eval(element.elts[2])
        except ValueError:
            problems.append(f"{name}: its search text is not a literal")
            continue

        checked += 1
        if not os.path.exists(path):
            problems.append(f"{name}: {path} does not exist")
            continue
        if path not in contents:
            contents[path] = open(path).read()
        found = contents[path].count(needle)
        if found != 1:
            # `apply()` needs exactly one. Zero is a moved line; more than one is a
            # mutation that would land in a place nobody measured.
            problems.append(f"{name}: {found} matches in {path}, expected exactly 1")

    print(f"{checked} probes, {len(contents)} files")
    for problem in problems:
        print("  " + problem)
    if problems:
        print(f"{len(problems)} probe(s) would abort a run. Fix before running the suite.")
        return 1
    print("every probe's text is present exactly once")
    return 0


if __name__ == "__main__":
    sys.exit(main())
