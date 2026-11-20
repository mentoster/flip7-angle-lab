#!/usr/bin/env python3
"""Publish locally built Android AAR + POM as public, token-free GitHub Maven.

This manually publishes the small versioned Maven repository on the `maven`
branch using the authenticated GitHub CLI. No CI/CD or credentials in files.

Run after:
  bash scripts/gradle-local.sh :hinge-angle-sdk:publishReleasePublicationToLocalReleaseRepository
  python scripts/publish-sdk-maven.py

Do not run this for the same version twice: uploaded versions are immutable.
"""

import base64
import json
import subprocess
import sys
from pathlib import Path

REPO = "mentoster/flip7-angle-lab"
MAVEN_ROOT = Path(__file__).resolve().parent.parent / "hinge-angle-sdk/build/maven-repository"
VERSION = "0.1.0"
PACKAGE = f"com/mentoster/flip7-hinge-sdk/{VERSION}"
MAVEN_ARTIFACT = f"flip7-hinge-sdk-{VERSION}"


def github(method, endpoint, data=None, missing_ok=False):
    command = ["gh", "api", "-X", method, f"repos/{REPO}/{endpoint}"]
    if data is not None:
        command += ["--input", "-"]
    result = subprocess.run(
        command,
        input=json.dumps(data) if data is not None else None,
        text=True, capture_output=True, check=False,
    )
    if result.returncode:
        if missing_ok and "HTTP 404" in result.stderr:
            return None
        raise RuntimeError(f"GitHub API {method} {endpoint}: {result.stderr[:400]}")
    return json.loads(result.stdout)


def main():
    main_ref = github("GET", "git/ref/heads/main")
    main_commit = main_ref["object"]["sha"]
    remote_ref = github("GET", "git/ref/heads/maven", missing_ok=True)
    if remote_ref is None:
        github("POST", "git/refs", {
            "ref": "refs/heads/maven", "sha": main_commit,
        })
        remote_ref = github("GET", "git/ref/heads/maven")
    parent = remote_ref["object"]["sha"]
    old_tree = github("GET", f"git/commits/{parent}")["tree"]["sha"]

    existing = github(
        "GET", f"contents/{PACKAGE}/{MAVEN_ARTIFACT}.pom?ref=maven",
        missing_ok=True,
    )
    if existing is not None:
        raise RuntimeError(
            f"Version {VERSION} already published. Bump module version first."
        )

    files = sorted(MAVEN_ROOT.rglob("*"))
    files = [f for f in files if f.is_file()]
    expected = [f"{PACKAGE}/{MAVEN_ARTIFACT}{ext}" for ext in (".aar", ".pom", ".module")]
    for path in expected:
        if not (MAVEN_ROOT / path).is_file():
            raise RuntimeError(f"Required Maven publication missing: {path}")

    entries = []
    for source in files:
        relative = source.relative_to(MAVEN_ROOT).as_posix()
        data = source.read_bytes()
        entry = {"path": relative, "mode": "100644", "type": "blob"}
        if source.suffix in (".aar", ".jar"):
            uploaded = github("POST", "git/blobs", {
                "content": base64.b64encode(data).decode("ascii"),
                "encoding": "base64",
            })
            entry["sha"] = uploaded["sha"]
        else:
            entry["content"] = data.decode("utf-8")
        entries.append(entry)

    new_tree = github("POST", "git/trees", {
        "base_tree": old_tree, "tree": entries,
    })["sha"]
    new_commit = github("POST", "git/commits", {
        "message": f"Publish com.mentoster:flip7-hinge-sdk:{VERSION} (AAR + Maven metadata)",
        "tree": new_tree, "parents": [parent],
    })["sha"]
    github("PATCH", "git/refs/heads/maven", {"sha": new_commit})

    print(f"Published {len(files)} files on {REPO}:maven at {new_commit[:12]}")
    print(
        "Maven repository: "
        f"https://raw.githubusercontent.com/{REPO}/maven"
    )
    print(f"Dependency: com.mentoster:flip7-hinge-sdk:{VERSION}")


if __name__ == "__main__":
    try:
        main()
    except (OSError, RuntimeError, ValueError) as error:
        print(f"Publication failed: {error}", file=sys.stderr)
        sys.exit(1)
