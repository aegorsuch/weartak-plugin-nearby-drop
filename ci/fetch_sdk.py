# SPDX-License-Identifier: GPL-3.0-or-later
"""Export an immutable, GPL-licensed WearTAK SDK source revision for CI."""

import hashlib
import os
from pathlib import Path
import subprocess
import sys
import tempfile

SDK_URL = "https://github.com/aegorsuch/weartak-plugin-sdk.git"
REVISION = "86bdc8b04797c2cf6caa30e78e796abac1c2a330"
ARCHIVE = Path(__file__).resolve().parents[1] / "build" / "sdk-source.tar"
LICENSE_MARKERS = ("GNU GENERAL PUBLIC LICENSE", "Version 3, 29 June 2007")
README_MARKER = "`GPL-3.0-or-later`"


def _git_environment():
    environment = os.environ.copy()
    environment["GIT_TERMINAL_PROMPT"] = "0"
    token = environment.get("CI_JOB_TOKEN")
    header = "JOB-TOKEN"
    if not token:
        token = environment.get("GITLAB_TOKEN")
        header = "PRIVATE-TOKEN"
    if token:
        if "\r" in token or "\n" in token:
            raise RuntimeError("Invalid SDK source credentials.")
        environment["GIT_CONFIG_COUNT"] = "1"
        environment["GIT_CONFIG_KEY_0"] = "http.https://git.tak.gov/.extraheader"
        environment["GIT_CONFIG_VALUE_0"] = f"{header}: {token}"
    return environment


def _git(arguments, environment, cwd=None, output=subprocess.PIPE):
    result = subprocess.run(
        ["git", *arguments],
        cwd=cwd,
        env=environment,
        stdout=output,
        stderr=subprocess.PIPE,
        check=False,
    )
    if result.returncode:
        raise RuntimeError("Pinned SDK source checkout failed; verify source access.")
    if output == subprocess.PIPE:
        return result.stdout.decode("utf-8").strip()
    return ""


def fetch():
    ARCHIVE.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="weartak-sdk-") as directory:
        checkout = Path(directory) / "sdk"
        environment = _git_environment()
        _git(
            ["clone", "--branch=main", "--single-branch", "--filter=blob:none",
             "--no-checkout", "--quiet", SDK_URL, str(checkout)],
            environment,
        )
        _git(["checkout", "--detach", "--quiet", REVISION], environment, cwd=checkout)
        if _git(["rev-parse", "HEAD"], environment, cwd=checkout) != REVISION:
            raise RuntimeError("SDK source revision mismatch.")

        license_text = (checkout / "LICENSE").read_text(encoding="utf-8")
        if not all(marker in license_text[:256] for marker in LICENSE_MARKERS):
            raise RuntimeError("Pinned SDK source lacks the verified GPL version 3 license.")
        readme = (checkout / "README.md").read_text(encoding="utf-8")
        if README_MARKER not in readme:
            raise RuntimeError("Pinned SDK source license declaration changed.")

        with tempfile.NamedTemporaryFile(
            dir=ARCHIVE.parent, prefix="sdk-source-", suffix=".tar", delete=False
        ) as temporary:
            temporary_path = Path(temporary.name)
            _git(
                # Repository bytes, so Windows and Linux builds match the pin.
                ["-c", "core.autocrlf=false", "archive", "--format=tar", f"--output={temporary_path}", REVISION],
                environment,
                cwd=checkout,
                output=temporary,
            )
        try:
            digest = hashlib.sha256(temporary_path.read_bytes()).hexdigest()
            temporary_path.replace(ARCHIVE)
        finally:
            if temporary_path.exists():
                temporary_path.unlink()

    print(f"Pinned WearTAK SDK source {REVISION}; archive SHA-256 {digest}.")


if __name__ == "__main__":
    try:
        fetch()
    except (OSError, RuntimeError, UnicodeError) as error:
        # Do not expose Git command output or credentials.
        sys.exit(f"SDK source preparation failed ({type(error).__name__}).")
