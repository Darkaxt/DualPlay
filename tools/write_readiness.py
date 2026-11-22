"""Writes a Coordinator gradle-build-gate readiness receipt (schema 1) for DualPlay.

Usage: python tools/write_readiness.py OUT.json --scope SCOPE --stage STAGE --requirement R ... \
           --reconciliation TEXT [--check ID=TASK ...]

Pins every tracked-or-untracked source file under app/, the runtime submodule's library sources,
the Gradle build files and the spec/plan documents, with LF-normalised JSON output.
"""
import argparse
import hashlib
import json
import pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
PIN_GLOBS = [
    "app/build.gradle",
    "app/src/main/**/*",
    "build.gradle",
    "settings.gradle",
    "gradle.properties",
    "gradle/wrapper/gradle-wrapper.properties",
    "local.properties",
    "docs/spec.md",
    "docs/plan-stage2-runtime.md",
    "runtime/LibretroDroid/libretrodroid/build.gradle",
    "runtime/LibretroDroid/libretrodroid/src/main/AndroidManifest.xml",
    "runtime/LibretroDroid/libretrodroid/src/main/cpp/*.cpp",
    "runtime/LibretroDroid/libretrodroid/src/main/cpp/*.h",
    "runtime/LibretroDroid/libretrodroid/src/main/cpp/CMakeLists.txt",
    "runtime/LibretroDroid/libretrodroid/src/main/cpp/renderers/**/*",
    "runtime/LibretroDroid/libretrodroid/src/main/java/**/*",
]


def sha256(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("out")
    parser.add_argument("--scope", required=True)
    parser.add_argument("--stage", required=True)
    parser.add_argument("--requirement", action="append", required=True)
    parser.add_argument("--reconciliation", required=True)
    parser.add_argument("--check", action="append", default=[], help="ID=gradle task")
    args = parser.parse_args()

    files = set()
    for pattern in PIN_GLOBS:
        for match in ROOT.glob(pattern):
            if match.is_file():
                files.add(match)
    pins = [{"path": p.relative_to(ROOT).as_posix(), "sha256": sha256(p)} for p in sorted(files)]

    checks = []
    for entry in args.check or ["assemble-debug=:app:assembleDebug"]:
        check_id, task = entry.split("=", 1)
        checks.append({"id": check_id, "tool": "gradle", "command": task.split(), "cwd": ROOT.as_posix(),
                       "environment": {}})

    receipt = {
        "schema": 1,
        "project": ROOT.as_posix(),
        "scope_id": args.scope,
        "stage_id": args.stage,
        "stages": [{"id": args.stage, "requirements": args.requirement, "source_complete": True}],
        "source_ready": True,
        "known_gaps": [],
        "source_pins": pins,
        "checks": checks,
        "reconciliation": args.reconciliation,
    }
    pathlib.Path(args.out).write_bytes((json.dumps(receipt, indent=1) + "\n").encode("utf-8"))
    print(f"{len(pins)} pins -> {args.out}")


if __name__ == "__main__":
    main()
